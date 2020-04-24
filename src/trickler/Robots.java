package trickler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * robots.txt parser
 *
 * @see <a href="https://tools.ietf.org/html/draft-koster-rep-01">Robots Exclusion Protocol</a>
 */
public class Robots {
    private static final Logger log = LoggerFactory.getLogger(Robots.class);
    private List<String> sitemaps = new ArrayList<>();
    private Integer crawlDelay;
    private List<String> allows = new ArrayList<>();
    private List<String> disallows = new ArrayList<>();

    public Robots(InputStream stream) throws IOException {
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
                        sitemaps.add(parts[1].trim());
                        break;
                    case "crawl-delay":
                        try {
                            crawlDelay = Integer.parseInt(parts[1].trim());
                        } catch (NumberFormatException e) {
                            log.debug("Error parsing crawl-delay value", e);
                        }
                        break;
                    case "allow":
                        allows.add(parts[1].trim());
                        break;
                    case "disallow":
                        disallows.add(parts[1].trim());
                        break;
                }
            }
        }
    }

    public String allowPattern() {
        return compile(allows);
    }

    public String disallowPattern() {
        return compile(disallows);
    }

    private static String compile(List<String> patterns) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = patterns.iterator(); it.hasNext(); ) {
            String pattern = it.next();
            pattern = pattern.replaceAll("\\*\\*+", "*");
            pattern = pattern.replaceFirst("\\*$", "");
            pattern = pattern.replaceFirst("\\$$", "*");
            String[] segments = pattern.split("\\*");
            for (int i = 0; i < segments.length; i++) {
                sb.append(Pattern.quote(segments[i]));
                if (i < segments.length - 1) {
                    sb.append(".*");
                }
            }
            if (it.hasNext()) {
                sb.append("|");
            }
        }
        return sb.toString();
    }

    public Integer crawlDelay() {
        return crawlDelay;
    }

    public List<String> sitemaps() {
        return sitemaps;
    }
}
