package org.netpreserve.chronicrawl;

import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static java.nio.file.StandardOpenOption.*;
import static org.netpreserve.jwarc.MediaType.HTML;

public class Exchange implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Exchange.class);
    private static final Set<String> IGNORED_EXTRA_HEADERS = Set.of(
            "content-length", "connection", "transfer-encoding", "host", "user-agent", "if-none-match",
            "if-modified-since", "referer", "te", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "ugprade-insecure-requests", "accept-encoding"
    );
    final Crawl crawl;
    final Origin origin;
    final Location location;
    final Location via;
    final FileChannel bufferFile;
    final Instant date = Instant.now();
    final Url url;
    final String method;
    final Map<String, String> extraHeaders;
    final Rule rule;
    public Visit revisitOf;
    public UUID warcId;
    public long requestPosition;
    public long requestLength;
    public long responsePosition;
    public long responseLength;
    HttpRequest httpRequest;
    HttpResponse httpResponse;
    InetAddress ip;
    int fetchStatus;
    UUID responseId;
    byte[] digest;
    Analysis analysis;
    Visit prevVisit;
    URI prevResponseId;
    long contentLength;
    String contentType;

    public Exchange(Crawl crawl, Origin origin, Location location, String method, Map<String, String> extraHeaders) throws IOException {
        this.crawl = crawl;
        this.origin = origin;
        this.location = location;
        this.method = method;
        this.extraHeaders = extraHeaders;
        Path tempFile = Files.createTempFile("chronicrawl", ".tmp");
        bufferFile = FileChannel.open(tempFile, READ, WRITE, DELETE_ON_CLOSE, TRUNCATE_EXISTING);
        url = location.url;
        this.via = location.viaPathId == null ? null : crawl.db.locations.find(location.viaOriginId, location.viaPathId);
        crawl.exchanges.add(this);
        List<Rule> rules = crawl.db.rules.listForOriginId(origin.id);
        this.rule = Rule.bestMatching(rules, location);
    }

    public void run() throws IOException {
        if (crawl.config.robotsPolicy == RobotsPolicy.IGNORE ||
                (crawl.config.robotsPolicy == RobotsPolicy.PAGES_ONLY && location.type != Location.Type.PAGE) ||
                parseRobots(origin.name + "/robots.txt", origin.robotsTxt).isAllowed(location.url.toString())) {
            fetch();
            crawl.storage.save(this);
        } else {
            fetchStatus = Status.ROBOTS_DISALLOWED;
        }
        finish();
        if (fetchStatus > 0) {
            process();
        }
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
        if (crawl.config.dedupeServer) {
            prevVisit = crawl.db.visits.findClosest(location.originId, location.pathId, date, method);
            if (prevVisit != null) {
                try {
                    crawl.storage.readResponse(prevVisit, (record, response) -> {
                        prevResponseId = response.id();
                        var headers = response.http().headers();
                        headers.first("ETag").ifPresent(s -> builder.addHeader("If-None-Match", s));
                        headers.first("Last-Modified").ifPresent(s -> builder.addHeader("If-Modified-Since", s));
                    });
                } catch (IOException e) {
                    log.warn("Failed to read back last visit", e);
                    prevVisit = null;
                }
            }
        }
        if (via != null) {
            builder.addHeader("Referer", via.url.toString());
        }
        for (var entry: extraHeaders.entrySet()) {
            if (!IGNORED_EXTRA_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                builder.setHeader(entry.getKey(), entry.getValue());
            }
        }

        log.info("Fetching {}", url);
        httpRequest = builder.build();

        try (Socket socket = url.connect(crawl.config.bindAddress, crawl.sslSocketFactory)) {
            ip = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress();
            socket.getOutputStream().write(httpRequest.serializeHeader());
            socket.getInputStream().transferTo(Channels.newOutputStream(bufferFile));
        } catch (UnknownHostException e) {
            fetchStatus = Status.DNS_LOOKUP_FAILED;
            log.debug("{} fetching {}", e, url);
            return;
        } catch (IOException e) {
            log.debug("{} fetching {}", e, url);
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
            this.contentLength = bodyLength;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Calculating " + crawl.config.warcDigestAlgorithm + " digest", e);
        }
    }

    private void process() {
        try {
            bufferFile.position(0);
            httpResponse = HttpResponse.parse(bufferFile);

            if (Status.isSuccess(fetchStatus) && revisitOf == null) {
                switch (location.type) {
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
            fetchStatus = Status.UNEXPECTED_RUNTIME_EXCEPTION;
        }
    }

    private void processPage() throws IOException {
        this.analysis = new Analysis(crawl, location, date, true);
        for (var resource : analysis.resources()) {
            crawl.enqueue(location, date, resource.url, Location.Type.TRANSCLUSION);
        }
        for (Url link : analysis.links()) {
            crawl.enqueue(location, date, link, Location.Type.PAGE);
        }
        if (analysis.screenshot != null) {
            crawl.db.screenshotCache.expire(100);
            crawl.db.screenshotCache.insert(url, date, analysis.screenshot);
        }
    }

    private void processRobots() throws IOException {
        byte[] content = httpResponse.body().stream().readNBytes(crawl.config.maxRobotsBytes);
        SimpleRobotRules rules = parseRobots(location.url.toString(), content);
        Short crawlDelay = null;
        if (rules.getCrawlDelay() > 0) {
            crawlDelay = (short)rules.getCrawlDelay();
        }
        System.out.println("robots " + rules.getSitemaps());
        for (String sitemapUrl : rules.getSitemaps()) {
            crawl.enqueue(location, date, location.url.resolve(sitemapUrl), Location.Type.SITEMAP);
        }
        crawl.db.origins.updateRobots(location.url.originId(), crawlDelay, content);
    }

    private void processSitemap() throws XMLStreamException, IOException {
        Sitemap.parse(httpResponse.body().stream(), entry -> {
            Url entryUrl = location.url.resolve(entry.loc);
            crawl.enqueue(location, date, entryUrl, entry.type);
            crawl.db.sitemapEntries.insertOrReplace(this.url, entryUrl, entry.changefreq, entry.priority, entry.lastmod == null ? null : entry.lastmod.toString());
        });
    }

    private void finish() {
        if (httpResponse != null) {
            contentType = httpResponse.contentType().base().toString();
        }
        Instant nextVisit = calcNextVisit();
        crawl.db.jdbi.inTransaction(h -> {
            crawl.db.origins.updateVisit(origin.id, date, date.plusMillis(calcDelayMillis()));
            crawl.db.locations.updateVisitData(location.url.originId(), location.url.pathId(), date, nextVisit);
            crawl.db.visits.insert(this);
            return null;
        });
        System.out.printf("%s %5d %10s %s %s %s %s\n", date, fetchStatus, contentLength,
                location.url, location.type, via != null ? via.url : "-", contentType != null ? contentType : "-");
        System.out.flush();

    }

    private Instant calcNextVisit() {
        // if there's a schedule applied, follow it
        if (rule != null && rule.scheduleId != null) {
            Schedule schedule = crawl.db.schedules.find(rule.scheduleId);
            if (schedule != null) {
                return schedule.apply(date.atZone(ZoneId.systemDefault())).toInstant();
            }
        }

        // if the sitemap tells us then follow it
        String changefreq = crawl.db.sitemapEntries.findChangefreq(location.originId, location.pathId);
        if (changefreq != null) {
            Duration nextDuration = Sitemap.parseChangefreq(changefreq);
            if (nextDuration != null) {
                return date.plus(nextDuration);
            }
        }

        // if we've visited before adapt based on whether the content changed
        Duration minDuration = crawl.config.minRevisit;
        Duration maxDuration = crawl.config.maxRevisit;
        if (location.lastVisit != null) {
            Duration duration = Duration.between(location.lastVisit, date);
            Duration nextDuration;
            if (revisitOf != null) { // content changed, revisit more frequently
                nextDuration = duration.dividedBy(2);
            } else { // content same, revisit less frequently
                nextDuration = duration.multipliedBy(2);
            }
            if (nextDuration.compareTo(minDuration) < 0) {
                nextDuration = minDuration;
            } else if (nextDuration.compareTo(maxDuration) > 0) {
                nextDuration = maxDuration;
            }
            return date.plus(nextDuration);
        }

        // if we know nothing start off by revisiting html frequently
        if (httpResponse != null && httpResponse.contentType().base().equals(HTML)) {
            return date.plus(crawl.config.initialRevisitHtml);
        }

        // and other media types a little less frequently
        return date.plus(crawl.config.initialRevisitOther);
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
