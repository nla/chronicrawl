package org.netpreserve.chronicrawl;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

public class BrowserTest {

    @Test
    public void test() throws IOException, InterruptedException, ExecutionException {
        try (TestServer server = new TestServer();
             Browser browser = assumeNewBrowser();
             BrowserTab tab = browser.createTab()) {

            var requested = new ConcurrentSkipListSet<>();
            tab.overrideDateAndRandom(Instant.ofEpochSecond(1234567890));
            tab.interceptRequests(request -> {
                requested.add(request.url().replaceFirst(".*/", ""));
                if (request.url().endsWith("/")) {
                    request.fulfill(200, "OK", Map.of("Content-Type", "text/html").entrySet(),
                            ("<img src=replaced.jpg>" +
                                    "<script>" +
                                    "document.title = 'at ' + Date.now();" +
                                    "</script>" +
                                    "<div style='height: 4000px'>tall</div>" +
                                    "<img src=lazy.jpg loading=lazy>").getBytes(StandardCharsets.UTF_8));
                }
            });
            tab.navigate(server.url() + "/").get();
//          assertFalse(requested.contains("lazy.jpg")); // probably shouldn't rely on the browser not loading it
            tab.scrollDown();
            assertEquals("at 1234567890000", tab.title());
            assertTrue(requested.contains("lazy.jpg"));
        }
    }

    public Browser assumeNewBrowser() throws IOException {
        try {
            return new Browser();
        } catch (IOException e) {
            assumeNoException("browser must be runnable", e);
            throw e;
        }
    }

}