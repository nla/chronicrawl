package trickler;

public class Config {
    /** User-Agent header to send to the server */
    String userAgent = "trickler";

    /** Maximum number of bytes to read from robots.txt */
    int maxRobotsBytes = 512 * 1024;

    /** Ignore robots.txt */
    boolean ignoreRobots = false;

    /** Maximum delay between requests */
    long maxDelayMillis = 30;
}
