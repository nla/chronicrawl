package trickler;

import com.github.f4b6a3.uuid.UuidCreator;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import static java.nio.file.StandardOpenOption.*;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

public class Exchange {
    private static final Logger log = LoggerFactory.getLogger(Exchange.class);
    private final Database db;
    private final WarcWriter warcWriter;
    private final Origin origin;
    private final Location location;
    private final FileChannel bufferFile;
    private final Instant date = Instant.now();
    private final Url url;
    private Instant fetchCompleteTime;
    private HttpRequest httpRequest;
    private HttpResponse httpResponse;
    private InetAddress ip;

    public Exchange(Origin origin, Location location, Database db, WarcWriter warcWriter) throws IOException {
        this.origin = origin;
        this.location = location;
        this.db = db;
        this.warcWriter = warcWriter;
        Path tempFile = Files.createTempFile("trickler", ".tmp");
        bufferFile = FileChannel.open(tempFile, READ, WRITE, DELETE_ON_CLOSE, TRUNCATE_EXISTING);
        url = location.url();
    }

    public void run() throws IOException {
        prepare();
        fetch();
        process();
        save();
        finish();
    }

    void prepare() {
        HttpRequest.Builder builder = new HttpRequest.Builder("GET", url.target())
                .addHeader("Host", url.hostInfo())
                .addHeader("User-Agent", "trickler")
                .addHeader("Connection", "close")
                .version(MessageVersion.HTTP_1_0);
        if (location.etag() != null) {
            builder.addHeader("If-None-Match", location.etag());
        }
        if (location.lastModified() != null) {
            builder.addHeader("If-Modified-Since", RFC_1123_DATE_TIME.format(location.lastModified()));
        }
        httpRequest = builder.build();
    }

    void fetch() throws IOException {
        try (Socket socket = url.connect()) {
            ip = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress();
            socket.getOutputStream().write(httpRequest.serializeHeader());
            socket.getInputStream().transferTo(Channels.newOutputStream(bufferFile));
        } finally {
            fetchCompleteTime = Instant.now();
        }
    }

    private void process() throws IOException {
        try {
            bufferFile.position(0);
            httpResponse = HttpResponse.parse(LengthedBody.create(bufferFile, ByteBuffer.allocate(8192).flip(), bufferFile.size()));
            if (hadSuccessStatus()) {
                switch (location.type()) {
                    case ROBOTS:
                        processRobots();
                        break;
                    case SITEMAP:
                        processSitemap();
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("Processing " + url + " failed", e);
        }
    }

    private void processRobots() throws IOException {
        Robots robots = new Robots(httpResponse.body().stream());
//
//        Robots.parse(httpResponse.body().stream(), new Robots.Handler() {
//            public void crawlDelay(long crawlDelay) {
//                if (crawlDelay >= 0 && crawlDelay < Short.MAX_VALUE) {
//                    db.updateOriginRobotsCrawlDelay(location.url().originId(), (short)crawlDelay);
//                }
//            }
//            public void sitemap(String url) {
//                enqueue(location.url().resolve(url), LocationType.SITEMAP, 2);
//            }
//        });
    }

    private void processSitemap() throws XMLStreamException, IOException {
        Sitemap.parse(httpResponse.body().stream(), entry -> {
            Url url = location.url().resolve(entry.loc);
            enqueue(url, entry.type, entry.type == LocationType.SITEMAP ? 3 : 10);
            db.updateLocationSitemapData(url.id(), entry.changefreq, entry.priority, entry.lastmod == null ? null : entry.lastmod.toString());
        });
    }

    private void enqueue(Url targetUrl, LocationType type, int priority) {
        db.tryInsertLocation(targetUrl, type, location.url().id(), date, priority);
        db.tryInsertLink(location.url().id(), targetUrl.id());
    }

    void save() throws IOException {
        bufferFile.position(0);
        UUID requestId = UuidCreator.getTimeOrdered();
        WarcRequest request = new WarcRequest.Builder(url.toURI())
                .recordId(requestId)
                .date(date)
                .body(httpRequest)
                .ipAddress(ip)
                .build();
        UUID responseId = UuidCreator.getTimeOrdered();
        WarcResponse response = new WarcResponse.Builder(url.toURI())
                .recordId(responseId)
                .date(date)
                .body(MediaType.HTTP_RESPONSE, bufferFile, bufferFile.size())
                .concurrentTo(request.id())
                .ipAddress(ip)
                .build();

        long requestPosition = warcWriter.position();
        warcWriter.write(request);

        long responsePosition = warcWriter.position();
        warcWriter.write(response);

        db.insertRecord(requestId, requestPosition);
        db.insertRecord(responseId, responsePosition);
        db.insertSnapshot(url.id(), date, httpResponse.status(), contentLength(),
                httpResponse.contentType().base().toString(), requestId, responseId);
    }

    private Long contentLength() {
        return httpResponse.headers().sole("Content-Length").map(Long::parseLong).orElse(null);
    }

    private void finish() {
        db.updateOriginVisit(origin.id(), date, origin.calculateNextVisit(date));
        db.updateLocationVisit(location.url().id(), date, date.plus(Duration.ofDays(1)), etag(), lastModified());
    }

    private boolean hadSuccessStatus() {
        return httpResponse.status() >= 200 && httpResponse.status() <= 299;
    }

    private String etag() {
        if (hadSuccessStatus()) {
            return httpResponse.headers().first("ETag").orElse(location.etag());
        } else {
            return location.etag();
        }
    }

    private Instant lastModified() {
        if (hadSuccessStatus()) {
            try {
                return httpResponse.headers().first("Last-Modified")
                        .map(s -> RFC_1123_DATE_TIME.parse(s, Instant::from)).orElse(location.lastModified());
            } catch (DateTimeParseException e) {
                return null;
            }
        } else {
            return location.lastModified();
        }
    }
}
