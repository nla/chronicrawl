package org.netpreserve.pagedrover;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

public class CrawlTest {
    private static TestServer testServer;

    @BeforeClass
    public static void startServer() throws IOException {
        testServer = new TestServer();
    }

    @AfterClass
    public static void stopServer() {
        testServer.stop();
    }

    @Test
    public void test() throws IOException {
        Database db = new Database("jdbc:h2:file:./data/testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        db.init();
        Config config = new Config();
        config.maxDelayMillis = 0;
        try (Crawl crawl = new Crawl(config, db)) {
            crawl.addSeed(testServer.url() + "/");
            crawl.step();
            crawl.step();
            crawl.step();
            crawl.step();
            crawl.step();
            crawl.step();
        }
    }
}
