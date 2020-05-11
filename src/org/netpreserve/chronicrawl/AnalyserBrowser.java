package org.netpreserve.chronicrawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.netpreserve.chronicrawl.Location.Type.TRANSCLUSION;

public class AnalyserBrowser {
    private static final Logger log = LoggerFactory.getLogger(AnalyserBrowser.class);
    private final Crawl crawl;
    private final boolean recordMode;
    private final Analysis analysis;

    public AnalyserBrowser(Crawl crawl, Analysis analysis, boolean recordMode) {
        this.analysis = analysis;
        this.crawl = crawl;
        this.recordMode = recordMode;

        try (BrowserTab tab = crawl.browser.createTab()) {
            if (crawl.config.scriptDeterminism) tab.overrideDateAndRandom(analysis.visitDate);
            tab.interceptRequests(this::onRequestIntercepted);
            try {
                tab.navigate(analysis.location.url.toString()).get(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
                throw new RuntimeException(e.getCause());
            } catch (TimeoutException e) {
                log.warn("Timed out waiting for page load {}", analysis.location.url);
            }

            analysis.screenshot = tab.screenshot();

            tab.scrollDown();

            tab.extractLinks().forEach(link -> analysis.addLink(new Url(link)));
            analysis.title = tab.title();
        }
    }

    private void onRequestIntercepted(BrowserRequest request) {
        try {
            Url subUrl = new Url(request.url());
            UUID lastVisitResponseId = crawl.db.records.lastResponseId(request.method(), subUrl.id(), analysis.visitDate);
            Analysis.ResourceType type = Analysis.ResourceType.valueOf(request.resourceType);
            analysis.addResource(request.method(), subUrl, type, lastVisitResponseId, "browser");
            if (lastVisitResponseId == null) {
                if (!recordMode) {
                    request.fail("InternetDisconnected");
                    return;
                }
                crawl.enqueue(analysis.location, Instant.now(), subUrl, TRANSCLUSION, 40);
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
}
