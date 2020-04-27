package trickler;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

public class TricklerTest {
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
        try (Trickler trickler = new Trickler(config, db)) {
            trickler.addSeed("http://localhost:/");
            trickler.step();
            trickler.step();
            trickler.step();
            trickler.step();
        }
    }
}
