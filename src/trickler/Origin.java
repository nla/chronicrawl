package trickler;

import java.time.Instant;

public class Origin {
    private final long id;
    private final String name;
    private final Long robotsCrawlDelay;
    private final Instant nextVisit;

    public Origin(long id, String name, Long robotsCrawlDelay, Instant nextVisit) {
        this.id = id;
        this.name = name;
        this.robotsCrawlDelay = robotsCrawlDelay;
        this.nextVisit = nextVisit;
    }

    public long id() {
        return id;
    }

    public Instant calculateNextVisit(Instant now) {
        return robotsCrawlDelay != null ? now.plusSeconds(robotsCrawlDelay) : now.plusSeconds(5);
    }

    public Instant nextVisit() {
        return nextVisit;
    }
}
