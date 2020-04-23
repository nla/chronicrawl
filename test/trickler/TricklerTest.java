package trickler;

import fi.iki.elonen.NanoHTTPD;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

public class TricklerTest {
    private static int port = 1234;
    private static TestSite testSite;

    public static class TestSite extends NanoHTTPD {

        @Override
        public Response serve(IHTTPSession session) {
            switch (session.getUri()) {
                case "/robots.txt":
                    return newFixedLengthResponse("Sitemap: /sitemap-index.xml\ncrawl-delay: 5\n");
                case "/sitemap-index.xml":
                    return newFixedLengthResponse("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
                            "<sitemap><loc>/sitemap.xml</loc></sitemap></sitemapindex>");
                case "/sitemap.xml":
                    return newFixedLengthResponse("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
                            "<url><loc>/page</loc><changefreq>daily</changefreq><priority>0.8</priority></url></urlset>");
                default:
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not found");
            }
        }

        public TestSite(int port) {
            super(port);
        }
    }

    @BeforeClass
    public static void startServer() throws IOException {
        testSite = new TestSite(port);
        testSite.start();
    }

    @AfterClass
    public static void stopServer() {
        testSite.stop();
    }

    @Test
    public void test() throws IOException {
        try (Trickler trickler = new Trickler()) {
//        try (Trickler trickler = new Trickler(new Database("jdbc:h2:mem:test;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "", ""))) {
            trickler.addSeed("http://localhost:1234/");
            trickler.step();
            trickler.step();
            trickler.step();
        }
    }
}
