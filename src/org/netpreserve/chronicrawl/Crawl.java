package org.netpreserve.chronicrawl;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Crawl implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Crawl.class);
    final Config config;
    final Database db;
    final SSLSocketFactory sslSocketFactory;
    private Browser browser;
    final Storage storage;
    final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();
    final AtomicBoolean paused = new AtomicBoolean(true);
    final Pywb pywb;
    final CloseableHttpAsyncClient httpClient;
    final ExternalArchive externalArchive;

    public Crawl(Config config, Database db) throws IOException {
        this.config = config;
        this.db = db;
        storage = new Storage(config, db);
        browser = new Browser();
        pywb = new Pywb(config);
        httpClient = HttpAsyncClients.createDefault();
        try {
            sslSocketFactory = SSLContext.getDefault().getSocketFactory();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        externalArchive = config.externalCdxUrl != null ? new ExternalArchive(config.externalCdxUrl) : null;
    }

    public synchronized Browser browser() {
        if (!browser.alive()) {
            log.error("Browser seems to have crashed, restarting it.");
            browser.close();
            try {
                browser = new Browser();
            } catch (IOException e) {
                log.error("Restarting browser failed. Pausing crawl.", e);
                paused.set(true);
                throw new UncheckedIOException(e);
            }
        }
        return browser;
    }

    public void addSeed(String url) {
        Url crawlUrl = new Url(url);
        Instant now = Instant.now();
        db.origins.tryInsert(crawlUrl.originId(), crawlUrl.origin(), now, CrawlPolicy.CONTINUOUS);
        db.origins.updateCrawlPolicy(crawlUrl.originId(), CrawlPolicy.CONTINUOUS);
        db.locations.tryInsert(crawlUrl.resolve("/robots.txt"), Location.Type.ROBOTS, null, 0, now);
        db.locations.tryInsert(crawlUrl, Location.Type.PAGE, null, 0, now);
    }

    void enqueue(Location via, Instant date, Url targetUrl, Location.Type type) {
        int depth = via.depth + 1;
        if (depth > config.maxDepth) return;

        if (db.origins.tryInsert(targetUrl.originId(), targetUrl.origin(), date, CrawlPolicy.TRANSCLUSIONS)) {
            db.locations.tryInsert(targetUrl.resolve("/robots.txt"), Location.Type.ROBOTS, null, 0, date);
        }
        db.locations.tryInsert(targetUrl, type, via.url, via == null ? 0 : via.depth + 1, date);
    }

    @Override
    public void close() {
        browser.close();
        db.close();
        storage.close();
        pywb.close();
        try {
            httpClient.close();
        } catch (IOException e) {
            log.warn("Exception closing HttpClient", e);
        }
    }

    public void step() throws IOException {
        if (paused.get()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return;
        }
        List<Origin> origins = db.origins.peek(1);
        if (origins.isEmpty()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return;
        }
        Origin origin = origins.get(0);
        Instant now = Instant.now();
        if (origin.nextVisit.isAfter(now)) {
            try {
                Thread.sleep(Duration.between(now, origin.nextVisit).toMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            }
        }
        Location location = db.locations.peek(origin.id);
        if (location == null) {
            db.origins.updateVisit(origin.id, Instant.now(), null);
            return;
        }
        try (Exchange exchange = new Exchange(this, origin, location, "GET", Collections.emptyMap())) {
            exchange.run();
        }
    }

    public void run() throws IOException {
        while (true) {
            step();
        }
    }

    public List<Visit> listVisits(Location location) {
        List<Visit> visits = db.visits.list(location.originId, location.pathId);
        if (externalArchive != null) {
            visits = new ArrayList<>(visits);
            visits.addAll(externalArchive.list(location.url.toString()));
            visits.sort(Comparator.comparing((Visit v) -> v.date).reversed());
        }
        return visits;
    }
}
