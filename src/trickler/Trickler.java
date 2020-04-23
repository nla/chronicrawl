package trickler;

import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;

import static java.nio.file.StandardOpenOption.*;

public class Trickler implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Trickler.class);
    private final Database db;
    private final WarcWriter warcWriter;

    public Trickler() throws IOException {
        this(new Database());
    }

    public Trickler(Database db) throws IOException {
        this.db = db;
        warcWriter = new WarcWriter(FileChannel.open(Paths.get("data/output.warc.gz"), WRITE, CREATE, APPEND));
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
    }

    public static void main(String args[]) throws IOException {
        try (Trickler trickler = new Trickler()) {
            trickler.addSeed("http://localhost/");
        }
    }

    public void step() throws IOException {
        Origin origin = db.selectNextOrigin();
        if (origin == null) {
            System.err.println("Nothing to do?");
            return;
        }
        Instant now = Instant.now();
        if (origin.nextVisit().isAfter(now)) {
            try {
                Thread.sleep(Duration.between(now, origin.nextVisit()).toMillis());
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            }
        }
        Location location = db.selectNextLocation(origin.id());
        Exchange exchange = new Exchange(origin, location, db, warcWriter);
        exchange.run();
    }

}
