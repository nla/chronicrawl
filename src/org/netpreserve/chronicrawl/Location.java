package org.netpreserve.chronicrawl;

import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

import java.time.Instant;

public class Location {
    public final long originId;
    public final long pathId;
    public final Url url;
    public final Type type;
    public final int depth;
    public final Long viaOriginId;
    public final Long viaPathId;
    public final Instant discovered;
    public final Instant lastVisit;
    public final Instant nextVisit;

    @JdbiConstructor
    public Location(long originId, long pathId, String origin, String path, Type locationType, int depth, Long viaOriginId, Long viaPathId,
                    Instant discovered, Instant lastVisit, Instant nextVisit) {
        this.originId = originId;
        this.pathId = pathId;
        this.url = new Url(origin + path);
        this.type = locationType;
        this.depth = depth;
        this.viaOriginId = viaOriginId;
        this.viaPathId = viaPathId;
        this.discovered = discovered;
        this.lastVisit = lastVisit;
        this.nextVisit = nextVisit;
    }

    public String href() {
        return "location?o=" + originId + "&p=" + pathId;
    }

    public enum Type {
        ROBOTS, SITEMAP, PAGE, TRANSCLUSION
    }
}
