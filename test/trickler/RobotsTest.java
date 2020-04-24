package trickler;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertEquals;

public class RobotsTest {
    @Test
    public void test() throws IOException {
        Robots robots = new Robots(new ByteArrayInputStream("Crawl-Delay: 5\nSitemap: /foo.xml\nAllow: /foo/bar\nAllow: /foo\nAllow: *.jpg".getBytes(ISO_8859_1)));
        assertEquals(5, robots.crawlDelay().intValue());
        assertEquals(List.of("/foo.xml"), robots.sitemaps());
        assertEquals("\\Q/foo/bar\\E|\\Q/foo\\E|\\Q\\E.*\\Q.jpg\\E", robots.allowPattern());
    }
}