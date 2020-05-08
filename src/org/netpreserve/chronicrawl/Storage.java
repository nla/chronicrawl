package org.netpreserve.chronicrawl;

import com.github.f4b6a3.uuid.UuidCreator;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

public class Storage implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Storage.class);
    private final Config config;
    private final Database db;
    private WarcWriter warcWriter;
    private int serial = 0;
    private UUID warcId;

    public Storage(Config config, Database db) throws IOException {
        this.config = config;
        this.db = db;
        openNextFile();
    }

    private synchronized void openNextFile() throws IOException {
        if (warcWriter != null) {
            warcWriter.close();
            warcWriter = null;
        }
        Path warcPath = Paths.get(config.warcFilename
                .replace("{TIMESTAMP}", config.warcTimestampFormat.format(Instant.now()))
                .replace("{SEQNO}", String.format("%05d", nextSerial())));
        warcWriter = new WarcWriter(FileChannel.open(warcPath, WRITE, CREATE_NEW));
        warcId = UuidCreator.getTimeOrdered();
        Instant date = Instant.now();
        warcWriter.write(new Warcinfo.Builder()
                .version(MessageVersion.WARC_1_1)
                .recordId(warcId)
                .date(date)
                .fields(Map.of("software", List.of("Chronicrawl/" + Config.version())))
                .build());
        db.warcs.insert(warcId, warcPath.toString(), date);
    }

    private synchronized int nextSerial() {
        int current = serial;
        serial++;
        if (serial >= 100000) serial = 0;
        return current;
    }

    synchronized void save(Exchange exchange) throws IOException {
        if (exchange.fetchStatus > 0 && exchange.httpRequest != null) {
            UUID requestId = exchange.id;
            WarcRequest request = new WarcRequest.Builder(exchange.url.toURI())
                    .version(MessageVersion.WARC_1_1)
                    .recordId(requestId)
                    .date(exchange.date)
                    .body(exchange.httpRequest)
                    .ipAddress(exchange.ip)
                    .build();
            if (config.warcMaxLengthBytes > 0 && warcWriter.position() > config.warcMaxLengthBytes) {
                openNextFile();
            }
            writeRecord(exchange, requestId, request, null);

            if (exchange.httpResponse != null) {
                exchange.bufferFile.position(0);
                UUID responseId = UuidCreator.getTimeOrdered();
                WarcCaptureRecord response = buildResponse(responseId, exchange, request);
                writeRecord(exchange, responseId, response, exchange.digest);
                exchange.responseId = responseId;
            }
        }
    }

    private void writeRecord(Exchange exchange, UUID recordId, WarcRecord record, byte[] payloadDigest) throws IOException {
        long offset = warcWriter.position();
        warcWriter.write(record);
        long length = warcWriter.position() - offset;
        db.records.insert(recordId, exchange.id, record.type(), warcId, offset, length, payloadDigest);
    }

    private WarcCaptureRecord buildResponse(UUID responseId, Exchange exchange, WarcRequest request) throws IOException {
        if (config.dedupeServer && exchange.httpResponse.status() == 304 && exchange.location.etagResponseId != null) {
            exchange.revisitOf = exchange.location.etagResponseId;
            return new WarcRevisit.Builder(exchange.url.toURI(), WarcRevisit.SERVER_NOT_MODIFIED_1_1)
                    .version(MessageVersion.WARC_1_1)
                    .recordId(responseId)
                    .date(exchange.date)
                    .body(MediaType.HTTP_RESPONSE, readHeaderOnly(exchange.bufferFile))
                    .concurrentTo(request.id())
                    .ipAddress(exchange.ip)
                    .refersTo(exchange.location.etagResponseId, exchange.url.toURI(), exchange.location.etagDate)
                    .build();
        }

        var duplicate = db.records.findResponseByPayloadDigest(exchange.url.id(), exchange.digest);
        if (config.dedupeDigest && duplicate.isPresent()) {
            exchange.revisitOf = duplicate.get().id;
            return new WarcRevisit.Builder(exchange.url.toURI(), WarcRevisit.IDENTICAL_PAYLOAD_DIGEST_1_1)
                    .version(MessageVersion.WARC_1_1)
                    .recordId(responseId)
                    .date(exchange.date)
                    .body(MediaType.HTTP_RESPONSE, readHeaderOnly(exchange.bufferFile))
                    .concurrentTo(request.id())
                    .ipAddress(exchange.ip)
                    .refersTo(duplicate.get().id, exchange.url.toURI(), duplicate.get().date)
                    .build();
        }

        return new WarcResponse.Builder(exchange.url.toURI())
                .version(MessageVersion.WARC_1_1)
                .recordId(responseId)
                .date(exchange.date)
                .body(MediaType.HTTP_RESPONSE, exchange.bufferFile, exchange.bufferFile.size())
                .concurrentTo(request.id())
                .ipAddress(exchange.ip)
                .payloadDigest(new WarcDigest(config.warcDigestAlgorithm, exchange.digest))
                .build();
    }

    void readResponseForVisit(UUID visitId, Util.IOConsumer<WarcResponse> consumer) throws IOException {
        readRecord(consumer, db.records.locateByVisitId(visitId, "response"));
    }

    void readResponse(UUID recordId, Util.IOConsumer<WarcResponse> consumer) throws IOException {
        readRecord(consumer, db.records.locate(recordId));
    }

    private void readRecord(Util.IOConsumer<WarcResponse> consumer, Database.RecordLocation recordLocation) throws IOException {
        try (FileChannel channel = FileChannel.open(Paths.get(recordLocation.path))) {
            channel.position(recordLocation.position);
            try (WarcReader warcReader = new WarcReader(channel)) {
                WarcRecord record = warcReader.next().orElse(null);
                if (record == null) throw new IOException("Record was missing");
                if (!(record instanceof WarcResponse)) throw new IOException(record.id() + " is a " + record.type() + " record not response");
                consumer.accept((WarcResponse) record);
            }
        }
    }

    static byte[] readHeaderOnly(ReadableByteChannel channel) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HttpParser parser = new HttpParser();
        parser.lenientResponse();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (!parser.isFinished() && !parser.isError()) {
            channel.read(buffer);
            buffer.flip();
            int start = buffer.position();
            parser.parse(buffer);
            baos.write(buffer.array(), start, buffer.position() - start);
        }
        return baos.toByteArray();
    }

    public synchronized void close() {
        try {
            warcWriter.close();
        } catch (IOException e) {
            log.error("Error closing storage", e);
        }
    }
}
