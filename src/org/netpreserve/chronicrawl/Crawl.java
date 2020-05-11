package org.netpreserve.chronicrawl;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Crawl implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Crawl.class);
    final Config config;
    final Database db;
    final Browser browser;
    final Storage storage;
    final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();
    final AtomicBoolean paused = new AtomicBoolean(true);
    final Pywb pywb;
    final CloseableHttpAsyncClient httpClient;

    public Crawl() throws IOException {
        this(new Config(), new Database());
    }

    public Crawl(Config config, Database db) throws IOException {
        this.config = config;
        this.db = db;
        storage = new Storage(config, db);
        browser = new Browser();
        pywb = new Pywb(config);
        httpClient = HttpAsyncClients.createDefault();
    }

    public void addSeed(String url) {
        Url crawlUrl = new Url(url);
        Instant now = Instant.now();
        if (db.origins.tryInsert(crawlUrl.originId(), crawlUrl.origin(), now, CrawlPolicy.CONTINUOUS)) {
            db.locations.tryInsert(crawlUrl.resolve("/robots.txt"), Location.Type.ROBOTS, crawlUrl.id(), now, 1);
        }
        db.locations.tryInsert(crawlUrl, Location.Type.PAGE, 0, now, 10);
    }

    void enqueue(long from, Instant date, Url targetUrl, Location.Type type, int priority) {
        if (db.origins.tryInsert(targetUrl.originId(), targetUrl.origin(), date, CrawlPolicy.TRANSCLUSIONS)) {
//            db.locations.tryInsert(targetUrl.resolve("/robots.txt"), Location.Type.ROBOTS, targetUrl.id(), date, 1);
        }
        db.locations.tryInsert(targetUrl, type, from, date, priority);
        db.tryInsertLink(from, targetUrl.id());
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

    public static void main(String[] args) throws IOException, InterruptedException {
        try (Crawl crawl = new Crawl()) {
            crawl.addSeed(args[0]);
            while (true) {
                crawl.step();
                Thread.sleep(1000);
            }
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
        Origin origin = db.origins.next();
        if (origin == null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return;
        }
        Instant now = Instant.now();
        if (origin.nextVisit.isAfter(now)) {
            try {
                Thread.sleep(Duration.between(now, origin.nextVisit).toMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            }
        }
        Location location = db.locations.next(origin.id);
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
}
