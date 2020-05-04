package org.netpreserve.pagedrover;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

public class Browser implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Browser.class);
    private static final List<String> executables = List.of("chromium-browser", "chromium", "google-chrome");
    private final Process process;
    private final WebSocket websocket;
    private final AtomicLong idSeq = new AtomicLong(0);
    public ConcurrentHashMap<String, Consumer<JsonObject>> sessionEventHandlers = new ConcurrentHashMap<>();
    private Map<Long, CompletableFuture<JsonObject>> calls = new ConcurrentHashMap<>();

    public Browser() throws IOException {
        Process process = null;
        for (String executable : executables) {
            try {
                var cmd = new ArrayList<String>();
                cmd.addAll(List.of(executable, "--headless", "--remote-debugging-port=0"));
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
            log.trace("< {}", rawMessage);
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
                        future.completeExceptionally(new BrowserException(message.getObject("error").getString("message")));
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
        String message = JsonWriter.string().object()
                .value("id", id)
                .value("sessionId", sessionId)
                .value("method", method)
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
            throw new BrowserException(method + ": " + e.getCause().getMessage(), e.getCause());
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

    public BrowserTab createTab() {
        return new BrowserTab(this);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        try (Browser browser = new Browser()) {
            Thread.sleep(3000);
        }
    }
}