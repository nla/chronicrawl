package trickler.browser;

import com.grack.nanojson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Map;

/**
 * Notionally a browser tab. At the protocol level this a devtools 'target'.
 */
public class Tab implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Tab.class);
    private final Browser browser;
    private final String targetId;
    private final String sessionId;
    private RequestInterceptor requestInterceptor;

    Tab(Browser browser) {
        this.browser = browser;
        targetId = browser.call("Target.createTarget",  Map.of("url", "about:blank")).getString("targetId");
        sessionId = browser.call("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true)).getString("sessionId");
        browser.sessionEventHandlers.put(sessionId, this::handleEvent);
    }

    JsonObject call(String method, Map<String, Object> params) {
        return browser.call(sessionId, method, params);
    }

    private void handleEvent(JsonObject event) {
        JsonObject params = event.getObject("params");
        switch (event.getString("method")) {
            case "Fetch.requestPaused":
                PausedRequest request = new PausedRequest(this, params.getString("requestId"), params.getObject("request"));
                if (requestInterceptor != null) {
                    requestInterceptor.onRequestPaused(request);
                }
                if (!request.handled) {
                    request.continueNormally();
                }
                break;
            default:
                log.warn("Unhandled event {}", event);
                break;
        }
    }

    @Override
    public void close() {
        browser.call("Target.closeTarget", Map.of("targetId", targetId));
        browser.sessionEventHandlers.remove(sessionId);
    }

    public void interceptRequests(RequestInterceptor requestHandler) {
        this.requestInterceptor = requestHandler;
        call("Fetch.enable", Map.of());
    }

    public void navigate(String url) {
        call("Page.navigate", Map.of("url", url));
    }
}
