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
                    Response response = newFixedLengthResponse(Response.Status.OK, "text/plain",
                            "Sitemap: /sitemap-index.xml\ncrawl-delay: 5\n");
                    response.addHeader("Etag", "\"123\"");
                    response.addHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT");
                    return response;
                case "/sitemap-index.xml":
                    return newFixedLengthResponse(Response.Status.OK, "application/xml",
                            "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
                            "<sitemap><loc>/sitemap.xml</loc></sitemap></sitemapindex>");
                case "/sitemap.xml":
                    return newFixedLengthResponse(Response.Status.OK, "application/xml",
                            "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
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
        Database db = new Database("jdbc:h2:file:./data/testdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        db.init();
        try (Trickler trickler = new Trickler(db)) {
            trickler.addSeed("http://localhost:1234/");
            trickler.step();
            trickler.step();
            trickler.step();
        }
    }
}
