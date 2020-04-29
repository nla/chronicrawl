package trickler;

import java.io.IOException;

public class Trickler {
    public static void main(String[] args) throws IOException {
        try (Crawl crawl = new Crawl();
             Webapp webapp = new Webapp(crawl, crawl.config.uiPort)) {
            crawl.run();
        }
    }
}
