package trickler;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

public class Location {
    private final Url url;
    private final LocationType type;
    private final String etag;
    private final Instant lastModified;
    final Long via;

    public Location(Url url, LocationType type, String etag, Instant lastModified, Long via) {
        this.url = requireNonNull(url, "url");
        this.type = requireNonNull(type, "type");
        this.etag = etag;
        this.lastModified = lastModified;
        this.via = via;
    }

    public Url url() {
        return url;
    }

    public LocationType type() {
        return type;
    }

    public String etag() {
        return etag;
    }

    public Instant lastModified() {
        return lastModified;
    }
}
