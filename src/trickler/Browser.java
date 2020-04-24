package trickler;

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
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

public class Browser implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Browser.class);

    private final Process process;
    private final WebSocket websocket;
    private final AtomicLong idSeq = new AtomicLong(0);
    private Map<Long, CompletableFuture<JsonObject>> calls = new ConcurrentHashMap<>();

    public Browser() throws IOException {
        process = new ProcessBuilder("chromium", "--headless", "--remote-debugging-port=0",
                "--enable-logging=stderr", "--v=1")
                .inheritIO()
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();
        websocket = new WebSocket(URI.create(readDevtoolsUrlFromStderr()));
    }

    private class WebSocket extends WebSocketClient {
        public WebSocket(URI uri) {
            super(uri);
        }

        public void onOpen(ServerHandshake handshakeData) {

        }
        public void onMessage(String rawMessage) {
            System.out.println(rawMessage);
            try {
                var message = JsonParser.object().from(rawMessage);
                if (message.has("method")) {

                } else {
                    long id = message.getLong("id");
                    CompletableFuture<JsonObject> future = calls.remove(id);
                    if (future == null) {
                        log.warn("Unexpected RPC response id {}", id);
                    } else if (message.has("error")) {
                        future.completeExceptionally(new RuntimeException(message.getObject("error").getString("message")));
                    } else {
                        future.complete(message.getObject("result"));
                    }
                }
            } catch (JsonParserException e) {
                log.debug("Received message: {}", rawMessage);
                log.warn("Error parsing devtools message", e);
            }
        }
        public void onClose(int code, String reason, boolean remote) {
            log.warn("WebSocket closed: {} {}", code, reason);
        }
        public void onError(Exception ex) {
            log.warn("WebSocket error", ex);
        }
    }

    public JsonObject call(String method, Map<String, Object> params) {
        long id = idSeq.incrementAndGet();
        String message = JsonWriter.string().object()
                .value("id", id)
                .value("method", method)
                .object("params", params)
                .end()
                .done();
        var future = new CompletableFuture<JsonObject>();
        calls.put(id, future);
        websocket.send(message);
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException)e.getCause();
            } else {
                throw new RuntimeException(e.getCause());
            }
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

    public void close() throws IOException {
        process.destroy();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IOException(e);
        } finally {
            process.destroyForcibly();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        try (Browser browser = new Browser()) {
            Thread.sleep(3000);
        }
    }

}