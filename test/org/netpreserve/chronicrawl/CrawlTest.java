package org.netpreserve.chronicrawl;

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
    public void testSqlite() throws IOException {
        test("jdbc:sqlite::memory:");
    }

    // we test against h2 as well to try to make sure we're keeping the sql reasonably portable
    // and to pick up any errors that sqlite's weak typing wouldn't hit
    @Test
    public void testH2() throws IOException {
        test("jdbc:h2:mem:test");
    }

    private void test(String dbUrl) throws IOException {
        try (Database db = new Database(dbUrl, "sa", "")) {
            db.init();
            Config config = new Config();
            config.maxDelayMillis = 0;
            try (Crawl crawl = new Crawl(config, db)) {
                crawl.addSeed(testServer.url() + "/");
                crawl.paused.set(false);
                crawl.step();
                crawl.step();
                crawl.step();
                crawl.step();
                crawl.step();
                crawl.step();
            }
        }
    }
}
