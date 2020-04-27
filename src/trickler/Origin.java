package trickler;

import java.time.Instant;

public class Origin {
    final long id;
    final String name;
    final Long robotsCrawlDelay;
    final Instant nextVisit;
    final byte[] robotsTxt;

    public Origin(long id, String name, Long robotsCrawlDelay, Instant nextVisit, byte[] robotsTxt) {
        this.id = id;
        this.name = name;
        this.robotsCrawlDelay = robotsCrawlDelay;
        this.nextVisit = nextVisit;
        this.robotsTxt = robotsTxt;
    }
}
