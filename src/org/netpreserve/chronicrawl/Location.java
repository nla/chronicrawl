package org.netpreserve.chronicrawl;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    public Location(ResultSet rs) throws SQLException {
        originId = rs.getLong("origin_id");
        pathId = rs.getLong("path_id");
        url = new Url(rs.getString("origin") + rs.getString("path"));
        type = Type.valueOf(rs.getString("location_type"));
        depth = rs.getInt("depth");
        viaOriginId = Database.getLongOrNull(rs, "via_origin_id");
        viaPathId = Database.getLongOrNull(rs, "via_path_id");
        discovered = Database.getInstant(rs, "discovered");
        lastVisit = Database.getInstant(rs, "last_visit");
        nextVisit = Database.getInstant(rs, "next_visit");
    }

    public String href() {
        return "location?o=" + originId + "&p=" + pathId;
    }

    public enum Type {
        ROBOTS, SITEMAP, PAGE, TRANSCLUSION
    }
}
