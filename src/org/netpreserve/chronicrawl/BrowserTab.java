package org.netpreserve.chronicrawl;

import com.grack.nanojson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * Notionally a browser tab. At the protocol level this a devtools 'target'.
 */
public class BrowserTab implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(BrowserTab.class);
    private static final String scrollDownJs = Util.resource("scrollDown.js");
    private static final String overrideDateAndRandomJs = Util.resource("overrideDateAndRandom.js");
    private final Browser browser;
    private final String targetId;
    private final String sessionId;
    private Consumer<BrowserRequest> requestInterceptor;
    private CompletableFuture<Double> loadFuture;
    private CompletableFuture<Void> networkIdleFuture;
    private ScheduledFuture<?> idleTimeout;
    private boolean closed;

    BrowserTab(Browser browser) {
        this.browser = browser;
        targetId = browser.call("Target.createTarget",  Map.of("url", "about:blank", "width", 1366, "height", 768)).getString("targetId");
        sessionId = browser.call("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true)).getString("sessionId");
        browser.sessionEventHandlers.put(sessionId, this::handleEvent);
        call("Page.enable", Map.of()); // for loadEventFired
        call("Page.setLifecycleEventsEnabled", Map.of("enabled", true)); // for networkidle
    }

    public JsonObject call(String method, Map<String, Object> params) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("closed");
        }
        return browser.call(sessionId, method, params);
    }

    private void handleEvent(JsonObject event) {
        JsonObject params = event.getObject("params");
        switch (event.getString("method")) {
            case "Fetch.requestPaused":
                BrowserRequest request = new BrowserRequest(this, params.getString("requestId"), params.getObject("request"), params.getString("resourceType"));
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
                if (loadFuture != null) {
                    loadFuture.complete(params.getDouble("timestamp"));
                }
                break;
            case "Page.lifecycleEvent":
                String eventName = params.getString("name");
                if (networkIdleFuture != null && eventName.equals("networkIdle") && params.getString("frameId").equals(targetId)) {
                    networkIdleFuture.complete(null);
//                    synchronized (this) {
//                        if (idleTimeout != null)
//                            idleTimeout.cancel(false);
//                        this.idleTimeout = browser.scheduledExecutor.schedule(() -> { networkIdleFuture.complete(null);}, 1000, TimeUnit.MILLISECONDS);
//                    }
                }
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

    public CompletableFuture<Void> navigate(String url) {
        if (loadFuture != null) {
            loadFuture.completeExceptionally(new InterruptedIOException("navigated away"));
        }
        loadFuture = new CompletableFuture<>();
        networkIdleFuture = new CompletableFuture<>();
        call("Page.navigate", Map.of("url", url));
        return CompletableFuture.allOf(networkIdleFuture, loadFuture);
    }

    public byte[] screenshot() {
        return Base64.getDecoder().decode(call("Page.captureScreenshot", Map.of("format", "jpeg")).getString("data"));
    }

    /**
     * Try to force js date and random functions to be deterministic. This doesn't actually succeed in making page
     * loading deterministic but it gets us closer. The random function is tries to match pywb.
     */
    public void overrideDateAndRandom(Instant date) {
        call("Page.addScriptToEvaluateOnNewDocument", Map.of("source", overrideDateAndRandomJs.replace("DATE", Long.toString(date.toEpochMilli()))));
    }

    private JsonObject eval(String expression) {
        return call("Runtime.evaluate", Map.of("expression", expression, "returnByValue", true)).getObject("result");
    }

    @SuppressWarnings("unchecked")
    public List<String> extractLinks() {
        return (List<String>)(List<?>)eval("Array.from(document.querySelectorAll('a[href], area[href]')).map(link => link.protocol+'//'+link.host+link.pathname+link.search+link.hash)").getArray("value");
    }

    public String title() {
        return eval("document.title").getString("value");
    }

    /**
     * Incrementally scroll the page to the very bottom to force lazy loading content to load.
     */
    public void scrollDown() {
        call("Runtime.evaluate", Map.of("expression", scrollDownJs, "awaitPromise", true));
    }
}
