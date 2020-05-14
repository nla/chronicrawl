package org.netpreserve.chronicrawl;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    public Origin(ResultSet rs) throws SQLException {
        id = rs.getLong("id");
        name = rs.getString("origin");
        discovered = Database.getInstant(rs, "discovered");
        lastVisit = Database.getInstant(rs, "last_visit");
        nextVisit = Database.getInstant(rs, "next_visit");
        robotsCrawlDelay = Database.getLongOrNull(rs, "robots_crawl_delay");
        robotsTxt = rs.getBytes("robots_txt");
        crawlPolicy = CrawlPolicy.valueOfOrNull(rs.getString("crawl_policy"));
    }

    public String href() {
        return "origin?id=" + id + "#queue";
    }

    public String robotsTxtString() {
        return robotsTxt == null ? null : new String(robotsTxt, StandardCharsets.UTF_8);
    }
}
