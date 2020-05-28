package org.netpreserve.chronicrawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.groupingBy;

public class Config implements Iterable<Config.Entry> {
    private static final Logger log = LoggerFactory.getLogger(Config.class);
    private static final Config defaults = new Config();

    /**
     * JDBC URL of database
     */
    @Section("Database")
    String dbUrl = "jdbc:sqlite:data/chronicrawl.sqlite3";

    /**
     * Database username
     */
    @Section("Database")
    String dbUser = "sa";

    /**
     * Database password
     */
    @Section("Database")
    String dbPassword = "";

    /**
     * URL prefix for the UI.
     */
    @Section("User Interface")
    public String uiContextPath = "";

    /**
     * Listening port of the web user interface
     */
    @Section("User Interface")
    int uiPort = 8080;

    /**
     * Name of the session cookie used by the Web UI
     */
    @Section("User Interface")
    String uiSessionCookie = "chronicrawlSessionId";

    /**
     * Seconds until the UI session expires
     */
    @Section("User Interface")
    long uiSessionExpirySecs = TimeUnit.DAYS.toSeconds(30);

    /**
     * User-Agent header to send to the server
     */
    @Section("Crawler")
    String userAgent = "Chronicrawl/" + version();

    /**
     * Bind address for the crawler (not the UI).
     */
    @Section("Crawler")
    InetAddress bindAddress;

    /**
     * Maximum number of bytes to read from robots.txt
     */
    @Section("Crawler")
    int maxRobotsBytes = 512 * 1024;

    /**
     * Ignore robots.txt
     */
    @Section("Crawler")
    boolean ignoreRobots = false;

    /**
     * Maximum delay between requests
     */
    @Section("Crawler")
    long maxDelayMillis = 30;

    /**
     * Maximum depth when following links
     */
    @Section("Crawler")
    int maxDepth = 10;

    /**
     * Override Date and Math.random to try to make scripts more deterministic (beware: this currently freezes time and
     * may break pages that expect time to advance)
     */
    @Section("Crawler")
    boolean scriptDeterminism = true;

    /**
     * Digest algorithm to use when calculating payload digest
     */
    @Section("Storage")
    String warcDigestAlgorithm = "sha1";

    /**
     * Template for construct WARC filenames. Variables: {TIMESTAMP} {SEQNO}
     */
    @Section("Storage")
    String warcFilename = "data/trickler-{TIMESTAMP}-{SEQNO}.warc";

    /**
     * Format of the {TIMESTAMP} variable in warcFilename
     */
    @Section("Storage")
    DateTimeFormatter warcTimestampFormat = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * Maximum length of a WARC file before a new one is created
     */
    @Section("Storage")
    long warcMaxLengthBytes = 1024 * 1024 * 1024; // 1 GiB

    /**
     * Write revisit records by giving the server an etag or last-modified and it returns status 304
     */
    @Section("Storage")
    boolean dedupeServer = true;

    /**
     * Write revisit records when the payload digest is unmodified
     */
    @Section("Storage")
    boolean dedupeDigest = true;

    /**
     * URL of the auth server (realm) to enable OpenID Connect authentication
     */
    @Section("User Interface")
    String oidcUrl;

    /**
     * OpenID Connect client id
     */
    @Section("User Interface")
    String oidcClientId;

    /**
     * OpenID Connect client secret
     */
    @Section("User Interface")
    String oidcClientSecret;

    /**
     * Command to run pywb for optional replay.
     */
    @Section("User Interface")
    String pywb;

    /**
     * Port to run Pywb on.
     */
    @Section("User Interface")
    int pywbPort = 8081;

    /**
     * Collection name to use when running Pywb.
     */
    @Section("User Interface")
    String pywbCollection = "chronicrawl";

    /**
     * The URL prefix (including collection name) to use when linking to Pywb. This may be different to pywbPort when
     * using a reverse proxy or running pywb separately.
     */
    @Section("User Interface")
    public String pywbUrl;

    static String version() {
        InputStream stream = Config.class.getResourceAsStream("/META-INF/maven/org.netpreserve/chronicrawl/pom.properties");
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
                set(field, value);
            } catch (NoSuchFieldException e) {
                log.warn("Unknown config option: {}", entry.getKey());
            }
        }
    }

    public void load(Map<?, ?> env) {
        for (Field field : getClass().getDeclaredFields()) {
            String altName = field.getName().replaceAll("([A-Z])", "_$1").toUpperCase(Locale.ROOT);
            Object value = env.get(field.getName());
            if (value == null) value = env.get(altName);
            if (value == null) continue;
            set(field, value.toString());
        }
    }

    public void set(String name, String value) {
        try {
            set(getClass().getDeclaredField(name), value);
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("No such field: " + name);
        }
    }

    private void set(Field field, String value) {
        try {
            if (field.getType().equals(Integer.TYPE)) {
                field.setInt(this, Integer.parseInt(value));
            } else if (field.getType().equals(String.class)) {
                field.set(this, value);
            } else if (field.getType().equals(Boolean.TYPE)) {
                field.setBoolean(this, Boolean.parseBoolean(value));
            } else if (field.getType().equals(Integer.class)) {
                field.set(this, Integer.parseInt(value));
            } else if (field.getType().equals(Integer.TYPE)) {
                field.setInt(this, Integer.parseInt(value));
            } else if (field.getType().equals(Long.TYPE)) {
                field.setLong(this, Long.parseLong(value));
            } else if (field.getType().equals(DateTimeFormatter.class)) {
                field.set(this, DateTimeFormatter.ofPattern(value).withZone(ZoneOffset.UTC));
            } else if (field.getType().equals(InetAddress.class)) {
                try {
                    field.set(this, InetAddress.getByName(value));
                } catch (UnknownHostException e) {
                    throw new RuntimeException("Unable to resolve " + value, e);
                }
            } else {
                throw new UnsupportedOperationException("Type conversion not implemented for " + field.getType());
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterator<Entry> iterator() {
        return Arrays.stream(getClass().getDeclaredFields())
                .filter(field -> {
                    Section section = field.getAnnotation(Section.class);
                    return !Modifier.isStatic(field.getModifiers()) && (section == null || !section.value().equals("Database"));
                })
                .map(Entry::new).iterator();
    }

    public Map<String,List<Entry>> sections() {
        return new TreeMap<>(StreamSupport.stream(spliterator(), false).collect(groupingBy(Entry::section)));
    }

    public class Entry {
        private final Field field;

        Entry(Field field) {
            this.field = field;
        }

        public String name() {
            return field.getName();
        }

        public String value() {
            try {
                return Objects.toString(field.get(Config.this), null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        public String defaultValue() {
            try {
                return Objects.toString(field.get(defaults), null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        public String section() {
            Section section = field.getAnnotation(Section.class);
            return section == null ? "Other" : section.value();
        }
    }

    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    @interface Section {
        String value();
    }
}
