package org.netpreserve.chronicrawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class BrowserExtract {
    private static final Logger log = LoggerFactory.getLogger(BrowserExtract.class);
    private final Crawl crawl;
    public final List<Subresource> subresources = new ArrayList<>();
    public String screenshot;

    public BrowserExtract(Crawl crawl) {
        this.crawl = crawl;
    }

    void process(String location, Instant date) {
        try (BrowserTab tab = crawl.browser.createTab()) {
            tab.interceptRequests(request -> {
                try {
                    Url url = new Url(request.url());
                    UUID lastVisitResponseId = crawl.db.records.lastResponseId(request.method(), url.id(), date);
                    subresources.add(new Subresource(request.method(), request.url(), request.resourceType, lastVisitResponseId));
                    if (lastVisitResponseId == null) {
                        request.fail("Failed");
                        return;
                    }
                    crawl.storage.readResponse(lastVisitResponseId, request::fulfill);
                } catch (IOException e) {
                    request.fail("Failed");
                }
            });
            try {
                tab.navigate(location).get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
                throw new RuntimeException(e.getCause());
            }

            this.screenshot = tab.screenshot();
        }
    }

    public static class Subresource {
        public final String method;
        public final String url;
        public final String type;
        public final UUID responseId;

        public Subresource(String method, String url, String type, UUID responseId) {
            this.method = method;
            this.url = url;
            this.type = type;
            this.responseId = responseId;
        }
    }
}
