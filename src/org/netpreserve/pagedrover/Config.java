package org.netpreserve.pagedrover;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    /** JDBC URL of database */
    String dbUrl = "jdbc:h2:file:./data/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE";

    /** Database username */
    String dbUser = "sa";

    /** Database password */
    String dbPassword = "";

    /** Listening port of the web user interface */
    int uiPort = 8080;

    /** User-Agent header to send to the server */
    String userAgent = "trickler/" + version();

    /** Maximum number of bytes to read from robots.txt */
    int maxRobotsBytes = 512 * 1024;

    /** Ignore robots.txt */
    boolean ignoreRobots = false;

    /** Maximum delay between requests */
    long maxDelayMillis = 30;

    /** Digest algorithm to use when calculating payload digest */
    String warcDigestAlgorithm = "sha1";

    /** Template for construct WARC filenames. Variables: {TIMESTAMP} {SEQNO} */
    String warcFilename = "data/trickler-{TIMESTAMP}-{SEQNO}.warc.gz";

    /** Format of the {TIMESTAMP} variable in warcFilename */
    DateTimeFormatter warcTimestampFormat = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneOffset.UTC);

    /** Maximum length of a WARC file before a new one is created */
    long warcMaxLengthBytes = 1024 * 1024 * 1024; // 1 GiB

    /** Write revisit records by giving the server an etag or last-modified and it returns status 304 */
    boolean dedupeServer = true;

    /** Write revisit records when the payload digest is unmodified */
    boolean dedupeDigest = true;

    static String version() {
        InputStream stream = Config.class.getResourceAsStream("/META-INF/maven/org.netpreserve/trickler/pom.properties");
        if (stream != null) {
            try (stream) {
                Properties properties = new Properties();
                properties.load(stream);
                return properties.getProperty("version");
            } catch (IOException e) {
                // uh oh
            }
        }
        try {
            String pom = Files.readString(Paths.get("pom.xml"));
            Matcher matcher = Pattern.compile("<version>([^<]*)</version>").matcher(pom);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (IOException e) {
            // uh oh
        }
        return "unknown";
    }

    public void load(Path path) throws IOException {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        for (var entry : properties.entrySet()) {
            try {
                var field = getClass().getDeclaredField(entry.getKey().toString());
                String value = entry.getValue().toString();
                if (field.getType().equals(Integer.TYPE)) {
                    field.setInt(this, Integer.parseInt(value));
                } else if (field.getType().equals(String.class)) {
                    field.set(this, value);
                } else if (field.getType().equals(Boolean.TYPE)) {
                    field.setBoolean(this, Boolean.parseBoolean(value));
                } else if (field.getType().equals(Long.TYPE)) {
                    field.setLong(this, Long.parseLong(value));
                } else if (field.getType().equals(DateTimeFormatter.class)) {
                    field.set(this, DateTimeFormatter.ofPattern(value).withZone(ZoneOffset.UTC));
                } else {
                    throw new UnsupportedOperationException("Type conversion not implemented for " + field.getType());
                }
            } catch (NoSuchFieldException e) {
                log.warn("Unknown config option: {}", entry.getKey());
            } catch (IllegalAccessException e) {
                throw new IOException(e);
            }
        }
    }
}
