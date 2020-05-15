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
            Visit subvisit = crawl.db.visits.findClosest(subUrl.originId(), subUrl.pathId(), analysis.visitDate, request.method());
            Analysis.ResourceType type = Analysis.ResourceType.valueOf(request.resourceType);
            analysis.addResource(request.method(), subUrl, type, subvisit, "browser");
            if (subvisit == null) {
                if (!recordMode) {
                    request.fail("InternetDisconnected");
                    return;
                }
                crawl.enqueue(analysis.location, Instant.now(), subUrl, TRANSCLUSION);
                Origin origin = crawl.db.origins.find(subUrl.originId());
                if (origin.crawlPolicy == CrawlPolicy.FORBIDDEN) {
                    log.trace("Subresource forbidden by crawl policy: {}", subUrl);
                    request.fail("AccessDenied");
                    return;
                }
                Location location = crawl.db.locations.find(subUrl.originId(), subUrl.pathId());
                Objects.requireNonNull(location);
                try (Exchange subexchange = new Exchange(crawl, origin, location, request.method(), request.headers())) {
                    subexchange.run();
                    if (subexchange.revisitOf != null) {
                        subvisit = subexchange.revisitOf;
                    } else {
                        subvisit = crawl.db.visits.find(subUrl.originId(), subUrl.pathId(), subexchange.date);
                    }
                }
            }
            if (subvisit.status < 0) {
                request.fail("Failed");
            } else {
                crawl.storage.readResponse(subvisit, request::fulfill);
            }
        } catch (IOException e) {
            request.fail("Failed");
        }
    }
}
