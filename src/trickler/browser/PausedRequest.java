package trickler.browser;

import com.grack.nanojson.JsonObject;
import org.netpreserve.jwarc.WarcResponse;

import java.io.IOException;
import java.util.*;

public class PausedRequest {
    private final Tab tab;
    private final String id;
    private final JsonObject request;
    boolean handled = false;

    PausedRequest(Tab tab, String id, JsonObject request) {
        this.tab = tab;
        this.id = id;
        this.request = request;
    }

    public String url() {
        return request.getString("url");
    }

    public String method() {
        return request.getString("method");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String,String> headers() {
        return (Map)Collections.unmodifiableMap(request.getObject("headers"));
    }


    public void fulfill(WarcResponse warcResponse) throws IOException {
        var headers = new ArrayList<Map.Entry<String, String>>();
        for (var entry : warcResponse.headers().map().entrySet()) {
            for (var value : entry.getValue()) {
                headers.add(new AbstractMap.SimpleEntry<>(entry.getKey(), value));
            }
        }
        fulfill(warcResponse.http().status(), warcResponse.http().reason().trim(),
                headers, warcResponse.http().body().stream().readNBytes(100 * 1024 * 1024));
    }

    public void fulfill(int status, String reason, Collection<Map.Entry<String, String>> headers, byte[] body) {
        enforceHandledOnce();
        List<Map<String, String>> headerList = new ArrayList<>();
        for (var entry: headers) {
            headerList.add(Map.of("name", entry.getKey(), "value", entry.getValue()));
        }
        tab.call("Fetch.fulfillRequest", Map.of("requestId", id,
                "responseCode", status,
                "responsePhrase", reason,
                "responseHeaders", headerList,
                "body", Base64.getEncoder().encodeToString(body)));
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
