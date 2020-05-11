package org.netpreserve.chronicrawl;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public class Location {
    public final Url url;
    public final Type type;
    public final String etag;
    public final Instant lastModified;
    public final Long via;
    public final int depth;
    public final UUID etagResponseId;
    public final Instant etagDate;
    public final Instant lastVisit;
    public final Instant nextVisit;
    public final int priority;
    public final String sitemapChangefreq;
    public final float sitemapPriority;
    public final String sitemapLastmod;

    public Location(Url url, Type type, String etag, Instant lastModified, Long via, int depth, UUID etagResponseId, Instant etagDate, Instant lastVisit, Instant nextVisit, int priority, String sitemapChangefreq, float sitemapPriority, String sitemapLastmod) {
        this.url = requireNonNull(url, "url");
        this.type = requireNonNull(type, "type");
        this.etag = etag;
        this.lastModified = lastModified;
        this.via = via;
        this.depth = depth;
        this.etagResponseId = etagResponseId;
        this.etagDate = etagDate;
        this.lastVisit = lastVisit;
        this.nextVisit = nextVisit;
        this.priority = priority;
        this.sitemapChangefreq = sitemapChangefreq;
        this.sitemapPriority = sitemapPriority;
        this.sitemapLastmod = sitemapLastmod;
    }

    public enum Type {
        ROBOTS, SITEMAP, PAGE, TRANSCLUSION
    }
}
