package trickler;

import static java.util.Objects.requireNonNull;

public class Location {
    private final Url url;
    private final LocationType type;

    public Location(Url url, LocationType type) {
        this.url = requireNonNull(url, "url");
        this.type = requireNonNull(type, "type");
    }

    public Url url() {
        return url;
    }

    public LocationType type() {
        return type;
    }
}
