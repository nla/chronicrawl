package trickler;

import com.github.f4b6a3.uuid.UuidCreator;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
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
    private Path warcPath;
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
        warcPath = Paths.get(config.warcFilename
                .replace("{TIMESTAMP}", config.warcTimestampFormat.format(Instant.now()))
                .replace("{SEQNO}", String.format("%05d", nextSerial())));
        warcWriter = new WarcWriter(FileChannel.open(warcPath, WRITE, CREATE_NEW));
        warcId = UuidCreator.getTimeOrdered();
        Instant date = Instant.now();
        warcWriter.write(new Warcinfo.Builder()
                .recordId(warcId)
                .date(date)
                .fields(Map.of("software", List.of("trickler/" + Config.version())))
                .build());
        db.insertWarc(warcId, warcPath.toString(), date);
    }

    private synchronized int nextSerial() {
        int current = serial;
        serial++;
        if (serial >= 100000) serial = 0;
        return current;
    }

    synchronized void save(Exchange exchange) throws IOException {
        if (exchange.httpRequest != null) {
            UUID requestId = UuidCreator.getTimeOrdered();
            WarcRequest request = new WarcRequest.Builder(exchange.url.toURI())
                    .recordId(requestId)
                    .date(exchange.date)
                    .body(exchange.httpRequest)
                    .ipAddress(exchange.ip)
                    .build();
            if (config.warcMaxLengthBytes > 0 && warcWriter.position() > config.warcMaxLengthBytes) {
                openNextFile();
            }
            long requestOffset = warcWriter.position();
            warcWriter.write(request);
            db.insertRecord(requestId, exchange.url.id(), exchange.date, request.type(), warcId, requestOffset);

            if (exchange.httpResponse != null) {
                exchange.bufferFile.position(0);
                UUID responseId = UuidCreator.getTimeOrdered();
                WarcResponse response = new WarcResponse.Builder(exchange.url.toURI())
                        .recordId(responseId)
                        .date(exchange.date)
                        .body(MediaType.HTTP_RESPONSE, exchange.bufferFile, exchange.bufferFile.size())
                        .concurrentTo(request.id())
                        .ipAddress(exchange.ip)
                        .build();
                long responseOffset = warcWriter.position();
                warcWriter.write(response);
                db.insertRecord(responseId, exchange.url.id(), exchange.date, response.type(), warcId, responseOffset);
                exchange.responseId = responseId;
            }
        }
    }

    void readResponse(UUID recordId, Util.IOConsumer<WarcResponse> consumer) throws IOException {
        var recordLocation = db.locateRecord(recordId);
        try (FileChannel channel = FileChannel.open(Paths.get(recordLocation.path))) {
            channel.position(recordLocation.position);
            try (WarcReader warcReader = new WarcReader(channel)) {
                WarcRecord record = warcReader.next().orElse(null);
                if (record == null) throw new IOException("Record was missing");
                consumer.accept((WarcResponse) record);
            }
        }
    }

    public synchronized void close() {
        try {
            warcWriter.close();
        } catch (IOException e) {
            log.error("Error closing storage", e);
        }
    }
}
