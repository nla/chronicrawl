package trickler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * robots.txt parser
 *
 * @see <a href="https://tools.ietf.org/html/draft-koster-rep-01">Robots Exclusion Protocol</a>
 */
public class Robots {
    private static final Logger log = LoggerFactory.getLogger(Robots.class);

    public static void parse(InputStream stream, Handler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.ISO_8859_1))) {
            while (true) {
                String line = reader.readLine();
                if (line == null) break;
                int commentStart = line.indexOf('#');
                if (commentStart >= 0) {
                    line = line.substring(0, commentStart);
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!line.contains(":")) continue;
                String[] parts = line.split(":", 2);
                String directive = parts[0].trim().toLowerCase(Locale.ROOT);
                switch (directive) {
                    case "sitemap":
                        handler.sitemap(parts[1].trim());
                        break;
                    case "crawl-delay":
                        try {
                            handler.crawlDelay(Integer.parseInt(parts[1].trim()));
                        } catch (NumberFormatException e) {
                            log.debug("Error parsing crawl-delay value", e);
                            continue;
                        }
                        break;

                }
            }
        }
    }

    public interface Handler {
        void crawlDelay(long crawlDelay);
        void sitemap(String url);
    }
}
