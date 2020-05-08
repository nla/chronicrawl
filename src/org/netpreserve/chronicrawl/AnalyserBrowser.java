package org.netpreserve.chronicrawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.netpreserve.chronicrawl.Location.Type.TRANSCLUSION;

public class AnalyserBrowser {
    private static final Logger log = LoggerFactory.getLogger(AnalyserBrowser.class);
    private final Crawl crawl;
    private final boolean recordMode;
    private final Analysis analysis;
    private final Instant date;
    private final Url url;

    public AnalyserBrowser(Crawl crawl, Analysis analysis, boolean recordMode) {
        this.analysis = analysis;
        this.crawl = crawl;
        this.url = analysis.url;
        this.date = analysis.date;
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
            
            analysis.screenshot = tab.screenshot();

            tab.scrollDown();

            tab.extractLinks().forEach(link -> analysis.links.add(new Url(link)));
            analysis.title = tab.title();
        }
    }

    private void onRequestIntercepted(BrowserRequest request) {
        try {
            Url subUrl = new Url(request.url());
            UUID lastVisitResponseId = crawl.db.records.lastResponseId(request.method(), subUrl.id(), date);
            Analysis.ResourceType type = Analysis.ResourceType.valueOf(request.resourceType);
            analysis.addResource(request.method(), subUrl, type, lastVisitResponseId, "browser");
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
}
