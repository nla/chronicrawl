package trickler.browser;

import org.junit.Test;
import trickler.TestServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class BrowserTest {

    @Test
    public void test() throws IOException, InterruptedException {
        try (TestServer server = new TestServer();
             Browser browser = new Browser();
             Tab tab = browser.createTab()) {

            tab.interceptRequests(new RequestInterceptor() {
                @Override
                public void onRequestPaused(PausedRequest request) {
                    System.out.println(request);
                    request.fulfill(200, "OK", Map.of("Content-Type", "text/html").entrySet(),
                            "<img src=replaced.jpg>".getBytes(StandardCharsets.UTF_8));
                }
            });
            tab.navigate(server.url() + "/");
            Thread.sleep(1000);

        }
    }

}