package org.netpreserve.chronicrawl;

import com.github.f4b6a3.uuid.UuidCreator;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

public class Storage implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Storage.class);
    private final Config config;
    private final Database db;
    private WarcWriter warcWriter;
    private static int serial = 0;
    private UUID warcId;

    public Storage(Config config, Database db) throws IOException {
        this.config = config;
        this.db = db;
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

    private static synchronized int nextSerial() {
        int current = serial;
        serial++;
        if (serial >= 100000) serial = 0;
        return current;
    }

    synchronized void save(Exchange exchange) throws IOException {
        if (exchange.fetchStatus > 0 && exchange.httpRequest != null) {
            UUID requestId = UuidCreator.getTimeOrdered();
            WarcRequest request = new WarcRequest.Builder(exchange.url.toURI())
                    .version(MessageVersion.WARC_1_1)
                    .recordId(requestId)
                    .date(exchange.date)
                    .body(exchange.httpRequest)
                    .ipAddress(exchange.ip)
                    .build();
            if (warcWriter == null || (config.warcMaxLengthBytes > 0 && warcWriter.position() > config.warcMaxLengthBytes)) {
                openNextFile();
            }
            exchange.warcId = warcId;
            exchange.requestPosition = warcWriter.position();
            warcWriter.write(request);
            exchange.requestLength = warcWriter.position() - exchange.requestPosition;

            if (exchange.httpResponse != null) {
                exchange.bufferFile.position(0);
                UUID responseId = UuidCreator.getTimeOrdered();
                WarcCaptureRecord response = buildResponse(responseId, exchange, request);
                exchange.responsePosition = warcWriter.position();
                warcWriter.write(response);
                exchange.responseLength = warcWriter.position() - exchange.responsePosition;
                exchange.responseId = responseId;
            }
        }
    }

    private WarcCaptureRecord buildResponse(UUID responseId, Exchange exchange, WarcRequest request) throws IOException {
        if (config.dedupeServer && exchange.httpResponse.status() == 304 && exchange.prevVisit != null) {
                exchange.revisitOf = exchange.prevVisit;
                return new WarcRevisit.Builder(exchange.url.toURI(), WarcRevisit.SERVER_NOT_MODIFIED_1_1)
                        .version(MessageVersion.WARC_1_1)
                        .recordId(responseId)
                        .date(exchange.date)
                        .body(MediaType.HTTP_RESPONSE, readHeaderOnly(exchange.bufferFile))
                        .concurrentTo(request.id())
                        .ipAddress(exchange.ip)
                        .refersTo(exchange.prevResponseId, exchange.url.toURI(), exchange.revisitOf.date)
                        .build();
        }

        WarcDigest payloadDigest = new WarcDigest(config.warcDigestAlgorithm, exchange.digest);
        Visit duplicate = db.visits.findByResponsePayloadDigest(exchange.location.originId, exchange.location.pathId, exchange.digest);
        if (config.dedupeDigest && duplicate != null) {
            try {
                WarcResponse priorResponse = readResponseHeader(duplicate);
                // we recheck the digest in the record as the database only keeps a truncated digest
                if (priorResponse.payloadDigest().equals(Optional.of(payloadDigest))) {
                    exchange.revisitOf = duplicate;
                    return new WarcRevisit.Builder(exchange.url.toURI(), WarcRevisit.IDENTICAL_PAYLOAD_DIGEST_1_1)
                            .version(MessageVersion.WARC_1_1)
                            .recordId(responseId)
                            .date(exchange.date)
                            .body(MediaType.HTTP_RESPONSE, readHeaderOnly(exchange.bufferFile))
                            .concurrentTo(request.id())
                            .ipAddress(exchange.ip)
                            .refersTo(priorResponse.id(), priorResponse.targetURI(), priorResponse.date())
                            .build();
                }
            } catch (IOException e) {
                log.warn("Failed reading prior record", e);
            }
        }

        return new WarcResponse.Builder(exchange.url.toURI())
                .version(MessageVersion.WARC_1_1)
                .recordId(responseId)
                .date(exchange.date)
                .body(MediaType.HTTP_RESPONSE, exchange.bufferFile, exchange.bufferFile.size())
                .concurrentTo(request.id())
                .ipAddress(exchange.ip)
                .payloadDigest(payloadDigest)
                .build();
    }

    WarcResponse readResponseHeader(Visit visit) throws IOException {
        var tmp = new WarcResponse[1];
        readResponse(visit, response -> tmp[0] = response);
        return tmp[0];
    }

    void readResponse(Visit visit, Util.IOConsumer<WarcResponse> consumer) throws IOException {
        readRecord(consumer, visit.warcId, visit.responsePosition);
    }

    private void readRecord(Util.IOConsumer<WarcResponse> consumer, UUID warcId, long position) throws IOException {
        String path = db.warcs.findPath(warcId);
        if (path == null) throw new NoSuchFileException("warc " + warcId.toString());
        try (FileChannel channel = FileChannel.open(Paths.get(path))) {
            channel.position(position);
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
            if (warcWriter != null) warcWriter.close();
        } catch (IOException e) {
            log.error("Error closing storage", e);
        }
    }
}
