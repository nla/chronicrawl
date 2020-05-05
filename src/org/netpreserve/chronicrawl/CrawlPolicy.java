package org.netpreserve.chronicrawl;

public enum CrawlPolicy {
    /** Never contact this origin under any circumstances */
    FORBIDDEN,

    /** Don't crawl pages on this origin directly but fetching content embedded in other websites is permitted */
    TRANSCLUSIONS,

    /** Continuous crawling of this origin is permitted */
    CONTINUOUS,
    ;

    public static CrawlPolicy valueOfOrNull(String name) {
        if (name == null) {
            return null;
        }
        return valueOf(name);
    }
}
