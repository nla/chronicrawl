package org.netpreserve.chronicrawl;

import java.time.Instant;

public class Origin {
    final long id;
    final String name;
    final Long robotsCrawlDelay;
    final Instant nextVisit;
    final byte[] robotsTxt;
    final CrawlPolicy crawlPolicy;

    public Origin(long id, String name, Long robotsCrawlDelay, Instant nextVisit, byte[] robotsTxt, CrawlPolicy crawlPolicy) {
        this.id = id;
        this.name = name;
        this.robotsCrawlDelay = robotsCrawlDelay;
        this.nextVisit = nextVisit;
        this.robotsTxt = robotsTxt;
        this.crawlPolicy = crawlPolicy;
    }
}
