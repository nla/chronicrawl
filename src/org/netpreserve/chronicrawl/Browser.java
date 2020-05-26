package org.netpreserve.chronicrawl;

import com.grack.nanojson.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.netpreserve.jwarc.WarcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

public class Browser implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Browser.class);
    private static final List<String> executables = List.of("chromium-browser", "chromium", "google-chrome",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
    private final Process process;
    private final WebSocket websocket;
    private final AtomicLong idSeq = new AtomicLong(0);
    private final ConcurrentHashMap<String, Consumer<JsonObject>> sessionEventHandlers = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<JsonObject>> calls = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduledExecutor = new ScheduledThreadPoolExecutor(1);

    public Browser() throws IOException {
        scheduledExecutor.setRemoveOnCancelPolicy(true);
        Process process = null;
        for (String executable : executables) {
            try {
                var cmd = new ArrayList<String>();
                cmd.add(executable);
                cmd.add("--headless");
                cmd.add("--remote-debugging-port=0");
                if (System.getenv("BROWSER_LOGGING") != null) cmd.addAll(List.of("--enable-logging=stderr", "--v=1"));
                process = new ProcessBuilder(cmd)
                        .inheritIO()
                        .redirectError(ProcessBuilder.Redirect.PIPE)
                        .start();
            } catch (IOException e) {
                continue;
            }
            break;
        }
        if (process == null) throw new IOException("Couldn't execute any of: " + executables);
        this.process = process;
        String url = readDevtoolsUrlFromStderr();
        log.trace("Connecting to {}", url);
        websocket = new WebSocket(URI.create(url));
        try {
            websocket.connectBlocking();
        } catch (InterruptedException e) {
            throw new IOException("Connect interrupted", e);
        }
    }

    private class WebSocket extends WebSocketClient {
        public WebSocket(URI uri) {
            super(uri);
            setConnectionLostTimeout(-1); // Chrome doesn't respond to WebSocket pings
        }

        public void onOpen(ServerHandshake handshakeData) {
            log.trace("WebSocket connected");
        }

        public void onMessage(String rawMessage) {
            log.trace("< {}", rawMessage.length() > 1024 ? rawMessage.substring(0, 1024) + "..." : rawMessage);
            try {
                var message = JsonParser.object().from(rawMessage);
                if (message.has("method")) {
                    if (message.has("sessionId")) {
                        var handler = sessionEventHandlers.get(message.getString("sessionId"));
                        if (handler != null) {
                            ForkJoinPool.commonPool().submit(() -> {
                                try {
                                    handler.accept(message);
                                } catch (Throwable t) {
                                    log.error("Exception handling browser event " + rawMessage, t);
                                }
                            });
                        } else {
                            log.warn("Event for unknown session {}", rawMessage);
                        }
                    }
                } else {
                    long id = message.getLong("id");
                    CompletableFuture<JsonObject> future = calls.remove(id);
                    if (future == null) {
                        log.warn("Unexpected RPC response id {}", id);
                    } else if (message.has("error")) {
                        future.completeExceptionally(new ErrorException(message.getObject("error").getString("message")));
                    } else {
                        future.complete(message.getObject("result"));
                    }
                }
            } catch (JsonParserException e) {
                log.debug("Received message: {}", rawMessage);
                log.warn("Error parsing devtools message", e);
            } catch (Throwable e) {
                log.error("Exception handling message", e);
                throw e;
            }
        }
        public void onClose(int code, String reason, boolean remote) {
            if (remote) {
                log.error("WebSocket closed: {} {}", code, reason);
            }
        }
        public void onError(Exception ex) {
            log.error("WebSocket error", ex);
        }
    }

    JsonObject call(String method, Map<String, Object> params) {
        return call(null, method, params);
    }

    JsonObject call(String sessionId, String method, Map<String, Object> params) {
        long id = idSeq.incrementAndGet();
        JsonStringWriter writer = JsonWriter.string().object()
                .value("id", id);
        if (sessionId != null) {
            writer.value("sessionId", sessionId);
        }
        String message = writer.value("method", method)
                .object("params", params)
                .end()
                .done();
        log.trace("> {} {}", message.length(), message.length() < 1024 ? message : message.substring(0, 1024) + "...");
        var future = new CompletableFuture<JsonObject>();
        calls.put(id, future);
        websocket.send(message);
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Call timed out: " + message, e);
        } catch (ExecutionException e) {
            throw new ErrorException(method + ": " + e.getCause().getMessage(), e.getCause());
        }
    }

    private String readDevtoolsUrlFromStderr() throws IOException {
        String devtoolsUrl;
        var rdr = new BufferedReader(new InputStreamReader(process.getErrorStream(), ISO_8859_1));
        CompletableFuture<String> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            String listenMsg = "DevTools listening on ";
            try {
                while (true) {
                    var line = rdr.readLine();
                    if (line == null) break;
                    if (!future.isDone() && line.startsWith(listenMsg)) {
                        future.complete(line.substring(listenMsg.length()));
                    }
                    log.debug("{}", line);
                }
            } catch (IOException e) {
                log.error("Error reading Chrome stderr", e);
                future.completeExceptionally(e);
            }
        });
        thread.setName("Chrome stderr");
        thread.start();

        try {
            devtoolsUrl = future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException)e.getCause();
            } else {
                throw new IOException(e);
            }
        }
        return devtoolsUrl;
    }

    public void close() {
        scheduledExecutor.shutdown();
        websocket.close();
        process.destroy();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // uh
        } finally {
            process.destroyForcibly();
        }
    }

    public Tab createTab() {
        return new Tab(this);
    }

    public static class ErrorException extends RuntimeException {
        public ErrorException(String message) {
            super(message);
        }

        public ErrorException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class Request {
        private static final Logger log = LoggerFactory.getLogger(Request.class);

        private final Tab tab;
        private final String id;
        private final JsonObject request;
        final String resourceType;
        boolean handled = false;

        Request(Tab tab, String id, JsonObject request, String resourceType) {
            this.tab = tab;
            this.id = id;
            this.request = request;
            this.resourceType = resourceType;
        }

        public String url() {
            return request.getString("url");
        }

        public String method() {
            return request.getString("method");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public Map<String,String> headers() {
            return (Map) Collections.unmodifiableMap(request.getObject("headers"));
        }

        public void fulfill(WarcResponse warcResponse) throws IOException {
            var headers = new ArrayList<Map.Entry<String, String>>();
            for (var entry : warcResponse.http().headers().map().entrySet()) {
                for (var value : entry.getValue()) {
                    headers.add(new AbstractMap.SimpleEntry<>(entry.getKey(), value));
                }
            }
            try {
                fulfill(warcResponse.http().status(), warcResponse.http().reason().trim(),
                        headers, warcResponse.http().body().stream().readNBytes(100 * 1024 * 1024));
            } catch (ErrorException e) {
                if (e.getMessage().equals("Fetch.fulfillRequest: Invalid InterceptionId.")) {
                    log.trace("Request cancelled {} {}", id, url());
                } else {
                    throw e;
                }
            }
        }

        public void fulfill(int status, String reason, Collection<Map.Entry<String, String>> headers, byte[] body) {
            enforceHandledOnce();
            List<Map<String, String>> headerList = new ArrayList<>();
            for (var entry: headers) {
                headerList.add(Map.of("name", entry.getKey(), "value", entry.getValue()));
            }
            Map<String, Object> params = new HashMap<>();
            params.put("requestId", id);
            params.put("responseCode", status);
            if (!reason.isBlank()) params.put("responsePhrase", reason);
            params.put("responseHeaders", headerList);
            params.put("body", Base64.getEncoder().encodeToString(body));
            tab.call("Fetch.fulfillRequest", params);
        }

        public void continueNormally() {
            enforceHandledOnce();
            tab.call("Fetch.continueRequest", Map.of("requestId", id));
        }

        public void fail(String errorReason) {
            enforceHandledOnce();
            tab.call("Fetch.failRequest", Map.of("requestId", id, "errorReason", errorReason));
        }

        private void enforceHandledOnce() {
            if (handled) {
                throw new IllegalStateException("Request already handled");
            } else {
                handled = true;
            }
        }

        @Override
        public String toString() {
            return "BrowserRequest{" +
                    "url='" + url() + '\'' +
                    ", method='" + method() + '\'' +
                    ", headers=" + headers() +
                    '}';
        }

    }

    /**
     * Notionally a browser tab. At the protocol level this a devtools 'target'.
     */
    public static class Tab implements Closeable {
        private static final Logger log = LoggerFactory.getLogger(Tab.class);
        private static final String scrollDownJs = Util.resource("scrollDown.js");
        private static final String overrideDateAndRandomJs = Util.resource("overrideDateAndRandom.js");
        private final Browser browser;
        private final String targetId;
        private final String sessionId;
        private Consumer<Request> requestInterceptor;
        private CompletableFuture<Double> loadFuture;
        private CompletableFuture<Void> networkIdleFuture;
        private boolean closed;

        Tab(Browser browser) {
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
                    Request request = new Request(this, params.getString("requestId"), params.getObject("request"), params.getString("resourceType"));
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

        public void interceptRequests(Consumer<Request> requestHandler) {
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
}