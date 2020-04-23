package trickler;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertEquals;

public class RobotsTest {
    private static class Handler implements Robots.Handler {
        private String url;
        private long crawlDelay;

        @Override
        public void crawlDelay(long crawlDelay) {
            this.crawlDelay = crawlDelay;
        }

        @Override
        public void sitemap(String url) {
            this.url = url;
        }
    }

    @Test
    public void test() throws IOException {
        Handler handler = new Handler();
        Robots.parse(new ByteArrayInputStream("Crawl-Delay: 5\nSitemap: /foo.xml\n".getBytes(ISO_8859_1)), handler);
        assertEquals(5, handler.crawlDelay);
        assertEquals("/foo.xml", handler.url);
    }
}