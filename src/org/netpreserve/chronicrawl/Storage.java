package org.netpreserve.chronicrawl;

import com.github.f4b6a3.uuid.UuidCreator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
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
        Files.createDirectories(warcPath.getParent());
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
        if (config.dedupeDigest && exchange.contentLength >= config.dedupeMinLength) {
            Visit duplicate = db.visits.findByResponsePayloadDigest(exchange.location.originId, exchange.location.pathId, exchange.digest);
            if (duplicate != null) {
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
        readResponse(visit, (record, response) -> tmp[0] = response);
        return tmp[0];
    }

    private FileChannel openWarc(UUID warcId) throws IOException {
        String path = db.warcs.findPath(warcId);
        if (path == null) throw new NoSuchFileException("warc " + warcId.toString());
        return FileChannel.open(Paths.get(path));
    }

    void readResponse(Visit visit, ResponseConsumer consumer) throws IOException {
        try (FileChannel channel = openWarc(visit.warcId)) {
            channel.position(visit.responsePosition);
            try (WarcReader warcReader = new WarcReader(channel)) {
                WarcRecord record = warcReader.next().orElse(null);
                if (record == null) throw new IOException("Record was missing");
                if (record instanceof WarcRevisit) {
                    WarcRevisit revisit = (WarcRevisit) record;
                    Url url = new Url(revisit.refersToTargetURI().orElseThrow(IOException::new).toString());
                    Visit visit1 = db.visits.find(url.originId(), url.pathId(), revisit.refersToDate().orElseThrow());
                    if (visit1 == null) throw new IOException("Revisit refers to missing record");
                    readResponse(visit1, (r, rs) -> consumer.accept(revisit, rs));
                } else if (record instanceof WarcResponse) {
                    consumer.accept((WarcResponse) record, (WarcResponse) record);
                } else {
                    throw new IOException(record.id() + " is a " + record.type() + " record not response or revisit");
                }
            }
        }
    }

    public String slurpHeaders(UUID warcId, long position) throws IOException {
        String warcHeader;
        String httpHeader = "";
        try (FileChannel channel = openWarc(warcId)) {
            channel.position(position);
            warcHeader = new Scanner(channel, ISO_8859_1).useDelimiter(Pattern.compile("\r?\n\r?\n")).next();
            channel.position(position);
            WarcRecord record = new WarcReader(channel).next().orElse(null);
            if (record instanceof WarcCaptureRecord) {
                var body = record.body();
                if (body != null) {
                    httpHeader = new Scanner(body, ISO_8859_1).useDelimiter(Pattern.compile("\r?\n\r?\n")).next();
                }
            }
        }
        return warcHeader + "\r\n\r\n" + httpHeader;
    }

    public String text(Visit visit) throws IOException {
        StringBuilder sb = new StringBuilder();
        readResponse(visit, (r, rsp) ->
                Jsoup.parse(rsp.payload().orElseThrow().body().stream(), null, r.target())
                        .traverse(
                                new NodeVisitor() {
                                    public void head(Node node, int depth) {
                                        if (node instanceof TextNode) sb.append(((TextNode) node).text());
                                        else if (Set.of("p", "h1", "h2", "h3", "h4", "h5", "tr", "li").contains(node.nodeName()))
                                            sb.append("\n");
                                    }

                                    @Override
                                    public void tail(Node node, int depth) {
                                        if (Set.of("br", "dd", "dt", "p", "h1", "h2", "h3", "h4", "h5").contains(node.nodeName()))
                                            sb.append("\n");
                                    }
                                }));
        return sb.toString();
    }

    public interface ResponseConsumer {
        void accept(WarcTargetRecord record, WarcResponse response) throws IOException;
    }

    static byte[] readHeaderOnly(ReadableByteChannel channel) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        HttpParser parser = new HttpParser();
        parser.lenientResponse();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (!parser.isFinished() && !parser.isError()) {
            int n = channel.read(buffer);
            if (n < 0) break;
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
