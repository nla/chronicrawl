package org.netpreserve.pagedrover;

import com.grack.nanojson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Notionally a browser tab. At the protocol level this a devtools 'target'.
 */
public class BrowserTab implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(BrowserTab.class);
    private final Browser browser;
    private final String targetId;
    private final String sessionId;
    private Consumer<BrowserRequest> requestInterceptor;
    private CompletableFuture<Double> loadFuture;
    private boolean closed;

    BrowserTab(Browser browser) {
        this.browser = browser;
        targetId = browser.call("Target.createTarget",  Map.of("url", "about:blank")).getString("targetId");
        sessionId = browser.call("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true)).getString("sessionId");
        browser.sessionEventHandlers.put(sessionId, this::handleEvent);
        call("Page.enable", Map.of()); // for loadEventFired
    }

    JsonObject call(String method, Map<String, Object> params) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("closed");
        }
        return browser.call(sessionId, method, params);
    }

    private void handleEvent(JsonObject event) {
        JsonObject params = event.getObject("params");
        switch (event.getString("method")) {
            case "Fetch.requestPaused":
                BrowserRequest request = new BrowserRequest(this, params.getString("requestId"), params.getObject("request"));
                if (requestInterceptor != null) {
                    try {
                        requestInterceptor.accept(request);
                    } catch (Throwable t) {
                        if (!request.handled) {
                            request.fail("Failed");
                        }
                        throw t;
                    }
                }
                if (!request.handled) {
                    request.continueNormally();
                }
                break;
            case "Page.loadEventFired":
                loadFuture.complete(params.getDouble("timestamp"));
                break;
            default:
                log.debug("Unhandled event {}", event);
                break;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            browser.call("Target.closeTarget", Map.of("targetId", targetId));
            browser.sessionEventHandlers.remove(sessionId);
        }
    }

    public void interceptRequests(Consumer<BrowserRequest> requestHandler) {
        this.requestInterceptor = requestHandler;
        call("Fetch.enable", Map.of());
    }

    public Future<Double> navigate(String url) {
        if (loadFuture != null) {
            loadFuture.completeExceptionally(new InterruptedIOException("navigated away"));
        }
        loadFuture = new CompletableFuture<>();
        call("Page.navigate", Map.of("url", url));
        return loadFuture;
    }
}
