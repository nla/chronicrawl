package org.netpreserve.pagedrover;

import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.netpreserve.pagedrover.browser.Tab;

import javax.xml.stream.XMLStreamException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static java.nio.file.StandardOpenOption.*;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static org.netpreserve.pagedrover.Location.Type.TRANSCLUSION;

public class Exchange implements Closeable {
    public static final int STATUS_ROBOTS_DISALLOWED = -9998;
    private static final Logger log = LoggerFactory.getLogger(Exchange.class);
    final Crawl crawl;
    private final Origin origin;
    final Location location;
    private final Location via;
    final FileChannel bufferFile;
    final Instant date = Instant.now();
    final Url url;
    private Instant fetchCompleteTime;
    HttpRequest httpRequest;
    HttpResponse httpResponse;
    InetAddress ip;
    private int fetchStatus;
    UUID responseId;
    byte[] digest;
    long bodyLength;

    public Exchange(Crawl crawl, Origin origin, Location location) throws IOException {
        this.crawl = crawl;
        this.origin = origin;
        this.location = location;
        Path tempFile = Files.createTempFile("pagedrover", ".tmp");
        bufferFile = FileChannel.open(tempFile, READ, WRITE, DELETE_ON_CLOSE, TRUNCATE_EXISTING);
        url = location.url();
        this.via = location.via == null ? null : crawl.db.selectLocationById(location.via);
        crawl.exchanges.add(this);
    }

    public void run() throws IOException {
        if (crawl.config.ignoreRobots || parseRobots(origin.name + "/robots.txt", origin.robotsTxt).isAllowed(location.url().toString())) {
            fetch();
            crawl.storage.save(this);
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
        if (crawl.config.dedupeServer && location.etag() != null) {
            builder.addHeader("If-None-Match", location.etag());
        }
        if (crawl.config.dedupeServer && location.lastModified() != null) {
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
        long bodyLength = 0;
        try {
            InputStream stream = httpResponse.body().stream();
            MessageDigest digest = MessageDigest.getInstance(crawl.config.warcDigestAlgorithm);
            byte[] buffer = new byte[8192];
            while (true) {
                int n = stream.read(buffer);
                if (n < 0) break;
                digest.update(buffer, 0, n);
                bodyLength += n;
            }
            this.digest = digest.digest();
            this.bodyLength = bodyLength;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Calculating " + crawl.config.warcDigestAlgorithm + " digest", e);
        }
    }

    private void process() throws IOException {
        bufferFile.position(0);
        httpResponse = HttpResponse.parse(bufferFile);

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
                try {
                    Url url = new Url(request.url());
                    System.out.println(url);
                    UUID lastVisitResponseId = crawl.db.selectLastResponseRecordId(request.method(), url.id());
                    if (lastVisitResponseId == null) {
                        enqueue(url, TRANSCLUSION, 40);
                        Origin origin = crawl.db.selectOriginById(url.originId());
                        if (origin.crawlPolicy == CrawlPolicy.FORBIDDEN) {
                            throw new IOException("Forbidden by crawl policy");
                        }
                        Location location = crawl.db.selectLocationById(url.id());
                        try (Exchange subexchange = new Exchange(crawl, origin, location)) {
                            subexchange.run();
                            lastVisitResponseId = subexchange.responseId;
                        }
                    }
                    crawl.storage.readResponse(lastVisitResponseId, request::fulfill);
                } catch (IOException e) {
                    request.fail("Failed");
                    log.error("Error replaying " + url, e);
                }
            });
            try {
                tab.navigate(location.url().toString()).get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
                throw new RuntimeException(e.getCause());
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
            enqueue(location.url().resolve(sitemapUrl), Location.Type.SITEMAP, 2);
        }
        crawl.db.updateOriginRobots(location.url().originId(), crawlDelay, content);
    }

    private void processSitemap() throws XMLStreamException, IOException {
        Sitemap.parse(httpResponse.body().stream(), entry -> {
            Url url = location.url().resolve(entry.loc);
            enqueue(url, entry.type, entry.type == Location.Type.SITEMAP ? 3 : 10);
            crawl.db.updateLocationSitemapData(url.id(), entry.changefreq, entry.priority, entry.lastmod == null ? null : entry.lastmod.toString());
        });
    }

    private void enqueue(Url targetUrl, Location.Type type, int priority) {
        crawl.db.tryInsertLocation(targetUrl, type, location.url().id(), date, priority);
        crawl.db.tryInsertLink(location.url().id(), targetUrl.id());
    }

    private void finish() {
        String contentType = httpResponse == null ? null : httpResponse.contentType().base().toString();
        Long contentLength = httpResponse == null ? null : httpResponse.headers().sole("Content-Length").map(Long::parseLong).orElse(null);
        crawl.db.updateOriginVisit(origin.id, date, date.plusMillis(calcDelayMillis()));

        if (hadSuccessStatus()) {
            String etag = httpResponse.headers().first("ETag").orElse(null);
            Instant lastModified = httpResponse.headers().first("Last-Modified")
                    .map(s -> RFC_1123_DATE_TIME.parse(s, Instant::from)).orElse(null);
            crawl.db.updateLocationEtag(location.url().id(), etag, lastModified, responseId, date);
        }

        crawl.db.updateLocationVisit(location.url().id(), date, date.plus(Duration.ofDays(1)));
        crawl.db.insertVisit(httpRequest.method(), url.id(), date, fetchStatus, contentLength, contentType, responseId);
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

    @Override
    public void close() throws IOException {
        bufferFile.close();
        crawl.exchanges.remove(this);
    }
}
