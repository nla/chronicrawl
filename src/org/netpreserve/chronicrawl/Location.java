package org.netpreserve.chronicrawl;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public class Location {
    private final Url url;
    private final Type type;
    private final String etag;
    private final Instant lastModified;
    final Long via;
    final int depth;
    final UUID etagResponseId;
    final Instant etagDate;

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

    public Url url() {
        return url;
    }

    public Type type() {
        return type;
    }

    public String etag() {
        return etag;
    }

    public Instant lastModified() {
        return lastModified;
    }

    public enum Type {
        ROBOTS, SITEMAP, PAGE, TRANSCLUSION
    }
}
