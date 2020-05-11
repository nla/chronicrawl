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

    public Location(Url url, Type type, String etag, Instant lastModified, Long via, int depth, UUID etagResponseId, Instant etagDate) {
        this.url = requireNonNull(url, "url");
        this.type = requireNonNull(type, "type");
        this.etag = etag;
        this.lastModified = lastModified;
        this.via = via;
        this.depth = depth;
        this.etagResponseId = etagResponseId;
        this.etagDate = etagDate;
    }

    public enum Type {
        ROBOTS, SITEMAP, PAGE, TRANSCLUSION
    }
}
