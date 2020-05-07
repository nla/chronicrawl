package org.netpreserve.chronicrawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;

import static org.netpreserve.chronicrawl.Location.Type.TRANSCLUSION;

public class BrowserAnalysis {
    private static final Logger log = LoggerFactory.getLogger(BrowserAnalysis.class);
    private final Crawl crawl;
    public final Set<Subresource> subresources = new ConcurrentSkipListSet<>();
    public final Set<Url> links = new ConcurrentSkipListSet<>();
    public final Url url;
    public final Instant date;
    public final String title;
    public final String screenshot;
    private final boolean recordMode;

    public BrowserAnalysis(Crawl crawl, Url url, Instant date, boolean recordMode) {
        this.crawl = crawl;
        this.url = url;
        this.date = date;
        this.recordMode = recordMode;

        try (BrowserTab tab = crawl.browser.createTab()) {
            if (crawl.config.scriptDeterminism) tab.overrideDateAndRandom(date);
            tab.interceptRequests(this::onRequestIntercepted);
            try {
                tab.navigate(url.toString()).get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
                throw new RuntimeException(e.getCause());
            }


            this.screenshot = tab.screenshot();

            tab.scrollDown();

            tab.extractLinks().forEach(link -> links.add(new Url(link)));
            this.title = tab.title();
        }
    }

    private void onRequestIntercepted(BrowserRequest request) {
        try {
            Url subUrl = new Url(request.url());
            UUID lastVisitResponseId = crawl.db.records.lastResponseId(request.method(), subUrl.id(), date);
            subresources.add(new Subresource(request.method(), subUrl, request.resourceType, lastVisitResponseId));
            if (lastVisitResponseId == null) {
                if (!recordMode) {
                    request.fail("InternetDisconnected");
                    return;
                }
                crawl.enqueue(url.id(), Instant.now(), subUrl, TRANSCLUSION, 40);
                Origin origin = crawl.db.origins.find(subUrl.originId());
                if (origin.crawlPolicy == CrawlPolicy.FORBIDDEN) {
                    log.trace("Subresource forbidden by crawl policy: {}", subUrl);
                    request.fail("AccessDenied");
                    return;
                }
                Location location = crawl.db.locations.find(subUrl.id());
                try (Exchange subexchange = new Exchange(crawl, origin, location, request.method(), request.headers())) {
                    subexchange.run();
                    if (subexchange.revisitOf != null) {
                        lastVisitResponseId = subexchange.revisitOf;
                    } else {
                        lastVisitResponseId = subexchange.responseId;
                    }
                }
            }
            crawl.storage.readResponse(lastVisitResponseId, request::fulfill);
        } catch (IOException e) {
            request.fail("Failed");
        }
    }

    public static class Subresource implements Comparable<Subresource> {
        public final String method;
        public final Url url;
        public final String type;
        public final UUID responseId;

        public Subresource(String method, Url url, String type, UUID responseId) {
            this.method = method;
            this.url = url;
            this.type = type;
            this.responseId = responseId;
        }

        @Override
        public int compareTo(Subresource o) {
            return url.compareTo(o.url);
        }
    }
}
