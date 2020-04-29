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
import java.util.UUID;

import static java.nio.file.StandardOpenOption.*;

public class Storage implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Storage.class);
    private final Database db;
    private final Path warcFile = Paths.get("data/output.warc.gz");
    private final WarcWriter warcWriter;

    public Storage(Database db) throws IOException {
        this.db = db;
        warcWriter = new WarcWriter(FileChannel.open(warcFile, WRITE, CREATE, APPEND));
    }

    void save(Exchange exchange) throws IOException {
        if (exchange.httpRequest != null) {
            UUID requestId = UuidCreator.getTimeOrdered();
            WarcRequest request = new WarcRequest.Builder(exchange.url.toURI())
                    .recordId(requestId)
                    .date(exchange.date)
                    .body(exchange.httpRequest)
                    .ipAddress(exchange.ip)
                    .build();
            exchange.requestOffset = warcWriter.position();
            warcWriter.write(request);

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
                exchange.responseOffset = warcWriter.position();
                warcWriter.write(response);
            }
        }
    }

    void readResponse(long recordOffset, Util.IOConsumer<WarcResponse> consumer) throws IOException {
        try (FileChannel channel = FileChannel.open(warcFile)) {
            channel.position(recordOffset);
            try (WarcReader warcReader = new WarcReader(channel)) {
                WarcRecord record = warcReader.next().orElse(null);
                if (record == null) throw new IOException("Record was missing");
                consumer.accept((WarcResponse) record);
            }
        }
    }

    public void close() {
        try {
            warcWriter.close();
        } catch (IOException e) {
            log.error("Error closing storage", e);
        }
    }
}
