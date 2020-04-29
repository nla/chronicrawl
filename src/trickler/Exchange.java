package trickler;

import com.github.f4b6a3.uuid.UuidCreator;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trickler.browser.Tab;

import javax.xml.stream.XMLStreamException;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static java.nio.file.StandardOpenOption.*;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static trickler.LocationType.TRANSCLUSION;

public class Exchange implements Closeable {
    public static final int STATUS_ROBOTS_DISALLOWED = -9998;
    private static final Logger log = LoggerFactory.getLogger(Exchange.class);
    private final Crawl crawl;
    private final Origin origin;
    private final Location location;
    private final Location via;
    private final FileChannel bufferFile;
    private final Instant date = Instant.now();
    private final Url url;
    private Instant fetchCompleteTime;
    private HttpRequest httpRequest;
    private HttpResponse httpResponse;
    private InetAddress ip;
    private Long requestOffset;
    private Long responseOffset;
    private int fetchStatus;

    public Exchange(Crawl crawl, Origin origin, Location location) throws IOException {
        this.crawl = crawl;
        this.origin = origin;
        this.location = location;
        Path tempFile = Files.createTempFile("trickler", ".tmp");
        bufferFile = FileChannel.open(tempFile, READ, WRITE, DELETE_ON_CLOSE, TRUNCATE_EXISTING);
        url = location.url();
        this.via = location.via == null ? null : crawl.db.selectLocationById(location.via);
        crawl.exchanges.add(this);
    }

    public void run() throws IOException {
        if (crawl.config.ignoreRobots || parseRobots(origin.name + "/robots.txt", origin.robotsTxt).isAllowed(location.url().toString())) {
            fetch();
            save();
            finish();
        } else {
            fetchStatus = STATUS_ROBOTS_DISALLOWED;
        }
        process();
    }

    private SimpleRobotRules parseRobots(String url, byte[] robotsTxt) {
        return new SimpleRobotRulesParser(Short.MAX_VALUE, 5).parseContent(url, robotsTxt, "text/plain", crawl.config.userAgent);
    }

    void fetch() throws IOException {
        HttpRequest.Builder builder = new HttpRequest.Builder("GET", url.target())
                .addHeader("Host", url.hostInfo())
                .addHeader("User-Agent", crawl.config.userAgent)
                .addHeader("Connection", "close")
                .version(MessageVersion.HTTP_1_0);
        if (location.etag() != null) {
            builder.addHeader("If-None-Match", location.etag());
        }
        if (location.lastModified() != null) {
            builder.addHeader("If-Modified-Since", RFC_1123_DATE_TIME.format(location.lastModified()));
        }
        if (via != null) {
            builder.addHeader("Referer", via.url().toString());
        }
        httpRequest = builder.build();
        try (Socket socket = url.connect()) {
            ip = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress();
            socket.getOutputStream().write(httpRequest.serializeHeader());
            socket.getInputStream().transferTo(Channels.newOutputStream(bufferFile));
        } finally {
            fetchCompleteTime = Instant.now();
        }
        bufferFile.position(0);
        httpResponse = HttpResponse.parse(LengthedBody.create(bufferFile, ByteBuffer.allocate(8192).flip(), bufferFile.size()));
        fetchStatus = httpResponse.status();
    }

    private void process() throws IOException {
        bufferFile.position(0);
        httpResponse = HttpResponse.parse(LengthedBody.create(bufferFile, ByteBuffer.allocate(8192).flip(), bufferFile.size()));
        try {
            if (hadSuccessStatus()) {
                switch (location.type()) {
                    case ROBOTS:
                        processRobots();
                        break;
                    case SITEMAP:
                        processSitemap();
                        break;
                    case PAGE:
                        processPage();
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("Processing " + url + " failed", e);
        }
    }

    private void processPage() {
        try (Tab tab = crawl.browser.createTab()) {
            tab.interceptRequests(request -> {
                Url url = new Url(request.url());
                System.out.println(url);
                Long subresponseOffset = crawl.db.selectLastVisitResponse(url.id());
                if (subresponseOffset == null) {
                    enqueue(url, TRANSCLUSION, 40);
                    Origin origin = crawl.db.selectOriginById(url.originId());
                    if (origin.crawlPolicy == CrawlPolicy.FORBIDDEN) {
                        request.fail("AccessDenied");
                        return;
                    }
                    Location location = crawl.db.selectLocationById(url.id());
                    try (Exchange subexchange = new Exchange(crawl, origin, location)) {
                        subexchange.run();
                        subresponseOffset = subexchange.responseOffset;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                if (subresponseOffset == null) {
                    throw new RuntimeException("subrequest failed");
                }
                try (FileChannel channel = FileChannel.open(crawl.warcFile)) {
                    channel.position(subresponseOffset);
                    try (WarcReader warcReader = new WarcReader(channel)) {
                        WarcRecord record = warcReader.next().orElse(null);
                        if (record == null) throw new IOException("Record was missing");
                        if (!(record instanceof WarcResponse))
                            throw new IOException("Got " + record.getClass() + " instead of WarcResponse");
                        WarcResponse response = (WarcResponse) record;
                        int maxLen = 100 * 1024 * 1024;
                        HttpResponse httpResponse = response.http();
                        byte[] body = httpResponse.body().stream().readNBytes(maxLen);
                        var headers = new ArrayList<Map.Entry<String, String>>();
                        for (var entry : httpResponse.headers().map().entrySet()) {
                            for (var value : entry.getValue()) {
                                headers.add(new AbstractMap.SimpleEntry<>(entry.getKey(), value));
                            }
                        }
                        request.fulfill(httpResponse.status(), httpResponse.reason().trim(), headers, body);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read record at position " + subresponseOffset, e);
                }
            });
            tab.navigate(location.url().toString());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void processRobots() throws IOException {
        byte[] content = httpResponse.body().stream().readNBytes(crawl.config.maxRobotsBytes);
        SimpleRobotRules rules = parseRobots(location.url().toString(), content);
        Short crawlDelay = null;
        if (rules.getCrawlDelay() > 0) {
            crawlDelay = (short)rules.getCrawlDelay();
        }
        System.out.println("robots " + rules.getSitemaps());
        for (String sitemapUrl : rules.getSitemaps()) {
            enqueue(location.url().resolve(sitemapUrl), LocationType.SITEMAP, 2);
        }
        crawl.db.updateOriginRobots(location.url().originId(), crawlDelay, content);
    }

    private void processSitemap() throws XMLStreamException, IOException {
        Sitemap.parse(httpResponse.body().stream(), entry -> {
            Url url = location.url().resolve(entry.loc);
            enqueue(url, entry.type, entry.type == LocationType.SITEMAP ? 3 : 10);
            crawl.db.updateLocationSitemapData(url.id(), entry.changefreq, entry.priority, entry.lastmod == null ? null : entry.lastmod.toString());
        });
    }

    private void enqueue(Url targetUrl, LocationType type, int priority) {
        crawl.db.tryInsertLocation(targetUrl, type, location.url().id(), date, priority);
        crawl.db.tryInsertLink(location.url().id(), targetUrl.id());
    }

    void save() throws IOException {
        if (httpRequest != null) {
            UUID requestId = UuidCreator.getTimeOrdered();
            WarcRequest request = new WarcRequest.Builder(url.toURI())
                    .recordId(requestId)
                    .date(date)
                    .body(httpRequest)
                    .ipAddress(ip)
                    .build();
            this.requestOffset = crawl.warcWriter.position();
            crawl.warcWriter.write(request);

            if (httpResponse != null) {
                bufferFile.position(0);
                UUID responseId = UuidCreator.getTimeOrdered();
                WarcResponse response = new WarcResponse.Builder(url.toURI())
                        .recordId(responseId)
                        .date(date)
                        .body(MediaType.HTTP_RESPONSE, bufferFile, bufferFile.size())
                        .concurrentTo(request.id())
                        .ipAddress(ip)
                        .build();
                this.responseOffset = crawl.warcWriter.position();
                crawl.warcWriter.write(response);
            }
        }
    }

    private void finish() {
        String contentType = httpResponse == null ? null : httpResponse.contentType().base().toString();
        Long contentLength = httpResponse == null ? null : httpResponse.headers().sole("Content-Length").map(Long::parseLong).orElse(null);
        crawl.db.updateOriginVisit(origin.id, date, date.plusMillis(calcDelayMillis()));
        crawl.db.updateLocationVisit(location.url().id(), date, date.plus(Duration.ofDays(1)), etag(), lastModified());
        crawl.db.insertVisit(url.id(), date, fetchStatus, contentLength, contentType, requestOffset, responseOffset);
        System.out.printf("%s %5d %10s %s %s %s %s\n", date, fetchStatus, contentLength != null ? contentLength : "-",
                location.url(), location.type(), via != null ? via.url() : "-", contentType != null ? contentType : "-");
        System.out.flush();
    }

    private long calcDelayMillis() {
        if (fetchStatus == STATUS_ROBOTS_DISALLOWED) return 0;
        long delay = origin.robotsCrawlDelay != null ? origin.robotsCrawlDelay * 1000 : 5000;
        if (delay > crawl.config.maxDelayMillis) delay = crawl.config.maxDelayMillis;
        return delay;
    }

    private boolean hadSuccessStatus() {
        return fetchStatus >= 200 && fetchStatus <= 299;
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

    @Override
    public void close() throws IOException {
        bufferFile.close();
        crawl.exchanges.remove(this);
    }
}
