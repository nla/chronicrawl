package trickler.browser;

import org.junit.Test;
import trickler.TestServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.Assume.assumeNoException;

public class BrowserTest {

    @Test
    public void test() throws IOException, InterruptedException, ExecutionException {
        try (TestServer server = new TestServer();
             Browser browser = assumeNewBrowser();
             Tab tab = browser.createTab()) {

            tab.interceptRequests(request -> {
                System.out.println(request);
                if (request.url().endsWith("/")) {
                    request.fulfill(200, "OK", Map.of("Content-Type", "text/html").entrySet(),
                            "<img src=replaced.jpg>".getBytes(StandardCharsets.UTF_8));
                }
            });
            tab.navigate(server.url() + "/").get();
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