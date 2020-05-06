package org.netpreserve.chronicrawl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class Origin {
    public final long id;
    public final String name;
    public final Instant discovered;
    public final Instant lastVisit;
    public final Instant nextVisit;
    public final Long robotsCrawlDelay;
    public final byte[] robotsTxt;
    public final CrawlPolicy crawlPolicy;

    public Origin(long id, String name, Instant discovered, Instant lastVisit, Instant nextVisit, Long robotsCrawlDelay, byte[] robotsTxt, CrawlPolicy crawlPolicy) {
        this.id = id;
        this.name = name;
        this.discovered = discovered;
        this.lastVisit = lastVisit;
        this.nextVisit = nextVisit;
        this.robotsCrawlDelay = robotsCrawlDelay;
        this.robotsTxt = robotsTxt;
        this.crawlPolicy = crawlPolicy;
    }

    public String robotsTxtString() {
        return new String(robotsTxt, StandardCharsets.UTF_8);
    }
}
