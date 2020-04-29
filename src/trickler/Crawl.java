package trickler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trickler.browser.Browser;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Crawl implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Crawl.class);
    final Config config;
    final Database db;
    final Browser browser;
    final Storage storage;
    final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();

    public Crawl() throws IOException {
        this(new Config(), new Database());
    }

    public Crawl(Config config, Database db) throws IOException {
        this.config = config;
        this.db = db;
        storage = new Storage(config, db);
        browser = new Browser();
    }

    public void addSeed(String url) {
        Url crawlUrl = new Url(url);
        Instant now = Instant.now();
        if (db.tryInsertOrigin(crawlUrl.originId(), crawlUrl.origin(), now)) {
            db.tryInsertLocation(crawlUrl.resolve("/robots.txt"), Location.Type.ROBOTS, crawlUrl.id(), now, 1);
        }
        db.tryInsertLocation(crawlUrl, Location.Type.PAGE, 0, now, 10);
    }

    @Override
    public void close() {
        browser.close();
        db.close();
        storage.close();
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
        Origin origin = db.selectNextOrigin();
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
        Location location = db.selectNextLocation(origin.id);
        if (location == null) {
            db.updateOriginVisit(origin.id, Instant.now(), null);
            return;
        }
        try (Exchange exchange = new Exchange(this, origin, location)) {
            exchange.run();
        }
    }

    public void run() throws IOException {
        while (true) {
            step();
        }
    }
}
