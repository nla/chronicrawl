package org.netpreserve.chronicrawl;

import com.github.f4b6a3.uuid.UuidCreator;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.time.ZoneOffset;
import java.util.*;

import static java.nio.file.StandardOpenOption.*;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

public class Exchange implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Exchange.class);
    private static Set<String> IGNORED_EXTRA_HEADERS = Set.of(
            "content-length", "connection", "transfer-encoding", "host", "user-agent", "if-none-match",
            "if-modified-since", "referer", "te", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "ugprade-insecure-requests", "accept-encoding"
    );

    final UUID id = UuidCreator.getTimeOrdered();
    final Crawl crawl;
    final Origin origin;
    final Location location;
    final Location via;
    final FileChannel bufferFile;
    final Instant date = Instant.now();
    final Url url;
    final String method;
    final Map<String, String> extraHeaders;
    public UUID revisitOf;
    HttpRequest httpRequest;
    HttpResponse httpResponse;
    InetAddress ip;
    int fetchStatus;
    UUID responseId;
    byte[] digest;
    long bodyLength;

    public Exchange(Crawl crawl, Origin origin, Location location, String method, Map<String, String> extraHeaders) throws IOException {
        this.crawl = crawl;
        this.origin = origin;
        this.location = location;
        this.method = method;
        this.extraHeaders = extraHeaders;
        Path tempFile = Files.createTempFile("chronicrawl", ".tmp");
        bufferFile = FileChannel.open(tempFile, READ, WRITE, DELETE_ON_CLOSE, TRUNCATE_EXISTING);
        url = location.url();
        this.via = location.via == null ? null : crawl.db.locations.find(location.via);
        crawl.exchanges.add(this);
    }

    public void run() throws IOException {
        if (crawl.config.ignoreRobots || parseRobots(origin.name + "/robots.txt", origin.robotsTxt).isAllowed(location.url().toString())) {
            fetch();
            finish();
            crawl.storage.save(this);
        } else {
            fetchStatus = Status.ROBOTS_DISALLOWED;
        }
        process();
    }

    private SimpleRobotRules parseRobots(String url, byte[] robotsTxt) {
        return new SimpleRobotRulesParser(Short.MAX_VALUE, 5).parseContent(url, robotsTxt, "text/plain", crawl.config.userAgent);
    }

    void fetch() throws IOException {
        HttpRequest.Builder builder = new HttpRequest.Builder(method, url.target())
                .addHeader("Host", url.hostInfo())
                .addHeader("User-Agent", crawl.config.userAgent)
                .addHeader("Connection", "close")
                .version(MessageVersion.HTTP_1_0);
        if (crawl.config.dedupeServer && location.etag() != null) {
            builder.addHeader("If-None-Match", location.etag());
        }
        if (crawl.config.dedupeServer && location.lastModified() != null) {
            builder.addHeader("If-Modified-Since", RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(location.lastModified()));
        }
        if (via != null) {
            builder.addHeader("Referer", via.url().toString());
        }
        for (var entry: extraHeaders.entrySet()) {
            if (!IGNORED_EXTRA_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                builder.setHeader(entry.getKey(), entry.getValue());
            }
        }

        log.info("Fetching {}", url);
        httpRequest = builder.build();
        try (Socket socket = url.connect()) {
            ip = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress();
            socket.getOutputStream().write(httpRequest.serializeHeader());
            socket.getInputStream().transferTo(Channels.newOutputStream(bufferFile));
        } catch (IOException e) {
            log.warn("Error fetching " + url, e);
            fetchStatus = Status.CONNECT_FAILED;
            return;
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
            if (Status.isSuccess(fetchStatus)) {
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

    private void processPage() throws IOException {
        Analysis analysis = new Analysis(url, date);
        crawl.storage.readResponse(responseId, response -> new AnalyserClassic(analysis).parseHtml(response));
        if (analysis.hasScript) {
            new AnalyserBrowser(crawl, analysis, true);
        }
        for (Url link : analysis.links()) {
            crawl.enqueue(url.id(), date, link, Location.Type.PAGE, 50);
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
            crawl.enqueue(url.id(), date, location.url().resolve(sitemapUrl), Location.Type.SITEMAP, 2);
        }
        crawl.db.origins.updateRobots(location.url().originId(), crawlDelay, content);
    }

    private void processSitemap() throws XMLStreamException, IOException {
        Sitemap.parse(httpResponse.body().stream(), entry -> {
            Url url = location.url().resolve(entry.loc);
            crawl.enqueue(url.id(), date, url, entry.type, entry.type == Location.Type.SITEMAP ? 3 : 10);
            crawl.db.locations.updateSitemapData(url.id(), entry.changefreq, entry.priority, entry.lastmod == null ? null : entry.lastmod.toString());
        });
    }

    private void finish() {
        String contentType = httpResponse == null ? null : httpResponse.contentType().base().toString();
        Long contentLength = httpResponse == null ? null : httpResponse.headers().sole("Content-Length").map(Long::parseLong).orElse(null);
        crawl.db.origins.updateVisit(origin.id, date, date.plusMillis(calcDelayMillis()));

        if (Status.isSuccess(fetchStatus)) {
            String etag = httpResponse.headers().first("ETag").orElse(null);
            Instant lastModified = httpResponse.headers().first("Last-Modified")
                    .map(s -> RFC_1123_DATE_TIME.parse(s, Instant::from)).orElse(null);
            crawl.db.locations.updateEtagData(location.url().id(), etag, lastModified, responseId, date);
        }

        crawl.db.locations.updateVisitData(location.url().id(), date, date.plus(Duration.ofDays(1)));
        crawl.db.visits.insert(id, httpRequest.method(), url.id(), date, fetchStatus, contentLength, contentType);
        System.out.printf("%s %5d %10s %s %s %s %s\n", date, fetchStatus, contentLength != null ? contentLength : "-",
                location.url(), location.type(), via != null ? via.url() : "-", contentType != null ? contentType : "-");
        System.out.flush();
    }

    private long calcDelayMillis() {
        if (fetchStatus == Status.ROBOTS_DISALLOWED) return 0;
        long delay = origin.robotsCrawlDelay != null ? origin.robotsCrawlDelay * 1000 : 5000;
        if (delay > crawl.config.maxDelayMillis) delay = crawl.config.maxDelayMillis;
        return delay;
    }

    @Override
    public void close() throws IOException {
        bufferFile.close();
        crawl.exchanges.remove(this);
    }
}
