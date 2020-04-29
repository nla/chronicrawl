package trickler;

import org.netpreserve.jwarc.WarcWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trickler.browser.Browser;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardOpenOption.*;

public class Crawl implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Crawl.class);
    final Config config;
    final Database db;
    final WarcWriter warcWriter;
    final Browser browser = new Browser();
    final Path warcFile = Paths.get("data/output.warc.gz");
    final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();

    public Crawl() throws IOException {
        this(new Config(), new Database());
    }

    public Crawl(Config config, Database db) throws IOException {
        this.config = config;
        this.db = db;
        warcWriter = new WarcWriter(FileChannel.open(warcFile, WRITE, CREATE, APPEND));
    }

    public void addSeed(String url) {
        Url crawlUrl = new Url(url);
        Instant now = Instant.now();
        if (db.tryInsertOrigin(crawlUrl.originId(), crawlUrl.origin(), now)) {
            db.tryInsertLocation(crawlUrl.resolve("/robots.txt"), LocationType.ROBOTS, crawlUrl.id(), now, 1);
        }
        db.tryInsertLocation(crawlUrl, LocationType.PAGE, 0, now, 10);
    }

    @Override
    public void close() {
        db.close();
        try {
            warcWriter.close();
        } catch (IOException e) {
            log.error("Error closing warcWriter", e);
        }
        browser.close();
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
