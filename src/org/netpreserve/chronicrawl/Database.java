package org.netpreserve.chronicrawl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.CaseStrategy;
import org.jdbi.v3.core.mapper.MapMapper;
import org.jdbi.v3.core.mapper.MapMappers;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.netpreserve.jwarc.WarcDigest;
import org.sqlite.SQLiteConfig;

import java.nio.ByteBuffer;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class Database implements AutoCloseable {
    private final HikariDataSource dataSource;
    public final IdGenerator ids = new IdGenerator(0); // TODO: claim unique nodeId
    public final ConfigDAO config;
    public final LocationDAO locations;
    public final OriginDAO origins;
    public final RuleDAO rules;
    public final ScheduleDAO schedules;
    public final ScreenshotCacheDAO screenshotCache;
    public final SitemapEntryDAO sitemapEntries;
    public final SessionDAO sessions;
    public final VisitDAO visits;
    public final WarcDAO warcs;
    final Jdbi jdbi;

    Database(String url, String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);

        if (url.startsWith("jdbc:sqlite:")) {
            SQLiteConfig sqlite = new SQLiteConfig();
            sqlite.enforceForeignKeys(true);
            sqlite.setSharedCache(true);
            sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
            sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            config.setDataSourceProperties(sqlite.toProperties());
            // FIXME: need to figure out how to correctly deal with SQLITE_BUSY before we can enable concurrent access
            config.setMaximumPoolSize(1);
        }

        dataSource = new HikariDataSource(config);
        this.jdbi = Jdbi.create(dataSource);
        jdbi.getConfig(MapMappers.class).setCaseChange(CaseStrategy.NOP);
        jdbi.installPlugin(new SqlObjectPlugin());
        jdbi.registerColumnMapper(Instant.class, (r, i, ctx) -> {
            long millis = r.getLong(i);
            return r.wasNull() ? null : Instant.ofEpochMilli(millis);
        });
        jdbi.registerArgument(new AbstractArgumentFactory<Instant>(Types.BIGINT) {
            protected Argument build(Instant value, ConfigRegistry config) {
                return (i, stmt, ctx) -> stmt.setLong(i, value.toEpochMilli());
            }
        });
        jdbi.registerColumnMapper(UUID.class, (r, i, ctx) -> {
            byte[] bytes = r.getBytes(i);
            if (bytes == null) return null;
            var bb = ByteBuffer.wrap(bytes);
            return new UUID(bb.getLong(), bb.getLong());
        });
        jdbi.registerArgument(new AbstractArgumentFactory<UUID>(Types.VARBINARY) {
            protected Argument build(UUID value, ConfigRegistry config) {
                return (i, stmt, ctx) -> stmt.setBytes(i, toBytes(value));
            }
        });

        this.config = jdbi.onDemand(ConfigDAO.class);
        this.origins = jdbi.onDemand(OriginDAO.class);
        this.locations = jdbi.onDemand(LocationDAO.class);
        this.rules = jdbi.onDemand(RuleDAO.class);
        this.schedules = jdbi.onDemand(ScheduleDAO.class);
        this.screenshotCache = jdbi.onDemand(ScreenshotCacheDAO.class);
        this.sitemapEntries = jdbi.onDemand(SitemapEntryDAO.class);
        this.sessions = jdbi.onDemand(SessionDAO.class);
        this.visits = jdbi.onDemand(VisitDAO.class);
        this.warcs = jdbi.onDemand(WarcDAO.class);
    }

    public static byte[] toBytes(UUID uuid) {
        if (uuid == null) return null;
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    boolean schemaExists() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getTables(null, null, "location", new String[]{"TABLE"}).next();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    void init() {
        runScript("schema.sql");
    }

    void migrate() {
        runScript("migrate.sql");
    }

    private void runScript(String script) {
        try (Connection connection = dataSource.getConnection()) {
            for (String sql : Util.resource(script).split(";")) {
                connection.createStatement().execute(sql);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Unable to initialize db", e);
        }
    }


    @Override
    public void close() {
        dataSource.close();
    }

    public interface ConfigDAO {
        @SqlQuery("SELECT name, value FROM config")
        @KeyColumn("name")
        @ValueColumn("value")
        Map<String, String> getAll();

        @SqlUpdate("INSERT INTO config (name, value) VALUES (?, ?)")
        void insert(String name, String value);

        @SqlUpdate("DELETE FROM config")
        void deleteAll();
    }

    @RegisterConstructorMapper(Origin.class)
    public interface OriginDAO {
        String fields = "o.id, o.origin, o.discovered, o.last_visit, o.next_visit, " +
                "o.robots_crawl_delay, o.robots_txt, (SELECT cp.name FROM crawl_policy cp WHERE cp.id = o.crawl_policy_id) as crawl_policy";

        @SqlQuery("SELECT " + fields + " FROM origin o WHERE id = ?")
        Origin find(long originId);


        @SqlQuery("SELECT " + fields + " FROM origin o " +
                "WHERE crawl_policy_id = (SELECT id FROM crawl_policy WHERE name = 'CONTINUOUS') AND next_visit IS NOT NULL " +
                "ORDER BY next_visit ASC LIMIT ?")
        List<Origin> peek(int limit);

        @SqlQuery("SELECT origin FROM origin WHERE id = ?")
        String findOrigin(long id);

        @SqlUpdate("INSERT INTO origin (id, origin, discovered, next_visit, crawl_policy_id) " +
                "VALUES (?, ?, ?, ?, (SELECT id FROM crawl_policy WHERE name = ?))")
        void insert(long id, String origin, Instant discovered, Instant nextVisit, CrawlPolicy crawlPolicy);

        default boolean tryInsert(long id, String name, Instant discovered, CrawlPolicy crawlPolicy) {
                String existing = findOrigin(id);
                if (existing == null) {
                    try {
                        insert(id, name, discovered, discovered, crawlPolicy);
                    } catch (Exception e) {
                        if (!(e.getCause() instanceof SQLIntegrityConstraintViolationException || e.getCause().getMessage().contains("SQLITE_CONSTRAINT"))) throw e;
                        existing = findOrigin(id); // handle insert race
                    }
                }
                if (existing != null && !existing.equals(name)) {
                    throw new RuntimeException("Hash collision between " + existing + " and " + name);
                }
                return existing != null;
        }

        @SqlUpdate("UPDATE origin SET next_visit = :nextVisit, last_visit = :lastVisit WHERE id = :originId")
        void updateVisit(long originId, Instant lastVisit, Instant nextVisit);

        @SqlUpdate("UPDATE origin SET robots_crawl_delay = :crawlDelay, robots_txt = :robotsTxt WHERE id = :originId")
        void updateRobots(long originId, Short crawlDelay, byte[] robotsTxt);

        @SqlUpdate("UPDATE origin SET crawl_policy_id = (SELECT id FROM crawl_policy WHERE name = :crawlPolicy) WHERE id = :id")
        void updateCrawlPolicy(long id, CrawlPolicy crawlPolicy);
    }

    @RegisterConstructorMapper(Location.class)
    public interface LocationDAO {
        String fields = "l.origin_id, l.path_id, o.origin, l.path, l.depth, " +
                "(SELECT lt.location_type FROM location_type lt WHERE lt.id = l.location_type_id) AS location_type, " +
                "l.via_origin_id, l.via_path_id, l.discovered, l.last_visit, l.next_visit";

        @SqlQuery("SELECT " + fields + " FROM location l " +
                "LEFT JOIN origin o ON o.id = l.origin_id " +
                "WHERE origin_id = ? AND path_id = ? LIMIT 1")
        Location find(long originId, long pathId);


        @SqlUpdate("INSERT INTO location (origin_id, path_id, path, location_type_id, depth, via_origin_id, via_path_id, discovered, next_visit) " +
                "VALUES (?, ?, ?, (SELECT id FROM location_type WHERE location_type = ?), ?, ?, ?, ?, ?)")
        void insert(long originId, long pathId, String pathref, String name, int depth, Long viaOriginId,
                    Long viaPathId, Instant discovered, Instant nextVisit);

        default void tryInsert(Url url, Location.Type type, Url viaUrl, int depth, Instant discovered) {
            do {
                var existingPath = findPath(url);
                if (existingPath.isEmpty()) {
                    try {
                        insert(url.originId(), url.pathId(), url.pathref(), type.name(), depth,
                                        viaUrl == null ? null : viaUrl.originId(), viaUrl == null ? null : viaUrl.pathId(),
                                        discovered, discovered);
                    } catch (Exception e) {
                        if (e.getCause().getMessage().contains("SQLITE_BUSY")) {
                            System.out.println("retry");
                            continue;
                        }
                        if (!(e.getCause() instanceof SQLIntegrityConstraintViolationException)) throw e;

                        existingPath = findPath(url); // handle insert race
                    }
                }
                if (existingPath.isPresent() && !existingPath.get().equals(url.pathref())) {
                    throw new RuntimeException("Hash collision between " + existingPath + " and " + url.pathref());
                }
            } while (false);
        }

        @SqlQuery("SELECT path FROM location WHERE origin_id = ? AND path_id = ?")
        Optional<String> findPath(long originId, long pathId);

        default Optional<String> findPath(Url url) {
            return findPath(url.originId(), url.pathId());
        }

        default Location peek(long originId) {
            return peek(originId, 1).stream().findFirst().orElse(null);
        }

        @SqlQuery("SELECT " + fields + " FROM location l " +
                "LEFT JOIN origin o ON o.id = l.origin_id " +
                "LEFT JOIN sitemap_entry se ON se.origin_id = l.origin_id AND se.path_id = l.path_id " +
                "WHERE l.next_visit <= ? AND l.origin_id = ? " +
                "ORDER BY l.location_type_id DESC, se.priority DESC, l.depth ASC, l.next_visit ASC LIMIT ?")
        List<Location> peek(Instant now, long originId, int limit);

        default List<Location> peek(long originId, int limit) {
            return peek(Instant.now(), originId, limit);
        }

        @SqlUpdate("UPDATE location SET next_visit = :nextVisit, last_visit = :lastVisit WHERE origin_id = :originId AND path_id = :pathId")
        void updateVisitData(long originId, long pathId, Instant lastVisit, Instant nextVisit);

        @SqlQuery("SELECT " + fields + " FROM location l " +
                "LEFT JOIN origin o ON o.id = l.origin_id " +
                "WHERE origin_id = ? AND path_id >= ? ORDER BY path_id ASC LIMIT ?")
        List<Location> paginate(long originId, long startPathId, int limit);
    }

    @RegisterConstructorMapper(Rule.class)
    public interface RuleDAO {
        @SqlQuery("SELECT rule.*, schedule.name AS schedule_name FROM rule " +
                "LEFT JOIN schedule ON schedule.id = rule.schedule_id " +
                "WHERE origin_id = ?")
        List<Rule> listForOriginId(long originId);

        @SqlUpdate("INSERT INTO rule (origin_id, pattern, schedule_id) VALUES (?, ?, ?)")
        void insert(long originId, String pattern, Long scheduleId);

        @SqlUpdate("UPDATE rule SET pattern = :newPattern, schedule_id = :scheduleId " +
                "WHERE origin_id = :originId AND pattern = :oldPattern")
        void update(long originId, String oldPattern, String newPattern, Long scheduleId);

        @SqlQuery("SELECT rule.*, schedule.name AS schedule_name FROM rule " +
                "LEFT JOIN schedule ON schedule.id = rule.schedule_id " +
                "WHERE rule.origin_id = ? AND rule.pattern = ?")
        Rule find(long originId, String pattern);

        @SqlUpdate("DELETE FROM rule WHERE origin_id = ? AND pattern = ?")
        void delete(long originId, String pattern);
    }

    @RegisterConstructorMapper(Schedule.class)
    public interface ScheduleDAO {
        @SqlQuery("SELECT * FROM schedule")
        List<Schedule> _list();

        default List<Schedule> list() {
            List<Schedule> schedules = new ArrayList<>(_list());
            schedules.sort(Comparator.naturalOrder());
            return schedules;
        }

        @SqlQuery("SELECT * FROM schedule WHERE id = ?")
        Schedule find(long id);

        @SqlUpdate("DELETE FROM schedule WHERE id = ?")
        void delete(long id);

        @SqlUpdate("UPDATE schedule SET name = :name, years = :years, months = :months, days = :days, " +
                "days_of_week = :daysOfWeek, hours_of_day = :hoursOfDay WHERE id = :id")
        void update(long id, String name, int years, int months, int days, int daysOfWeek, int hoursOfDay);

        @SqlUpdate("INSERT INTO schedule (id, name, years, months, days, days_of_week, hours_of_day) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")
        void insert(long id, String name, int years, int months, int days, int dayOfWeek, int hourOfDay);
    }

    public interface SitemapEntryDAO {
        @SqlUpdate("DELETE FROM sitemap_entry WHERE origin_id = ? AND path_id = ?")
        void _delete(long originId, long pathId);

        @SqlUpdate("INSERT INTO sitemap_entry (origin_id, path_id, sitemap_origin_id, sitemap_path_id, changefreq, priority, lastmod) VALUES (?, ?, ?, ?, ?, ?, ?)")
        void _update(long originid, long pathId, long sitemapOriginId, long sitemapPathId, Sitemap.ChangeFreq changeFreq, Float priority, String lastmod);

        default void insertOrReplace(Url url, Url sitemapUrl, Sitemap.ChangeFreq changeFreq, Float priority, String lastmod) {
            _delete(url.originId(), url.pathId());
            _update(url.originId(), url.pathId(), sitemapUrl.originId(), sitemapUrl.pathId(), changeFreq, priority, lastmod);
        }

        @SqlQuery("SELECT changefreq FROM sitemap_entry WHERE origin_id = ? AND path_id = ? LIMIT 1")
        String findChangefreq(long originId, long pathId);
    }

    @RegisterConstructorMapper(Visit.class)
    @RegisterConstructorMapper(CdxLine.class)
    public interface VisitDAO extends SqlObject {
        @SqlQuery("SELECT *, (SELECT method FROM method WHERE method.id = v.method_id) AS method, " +
                "(SELECT ct.content_type FROM content_type ct WHERE ct.id = v.content_type_id) AS content_type " +
                "FROM visit v WHERE origin_id = ? AND path_id = ? AND date = ?")
        Visit find(long originId, long pathId, Instant date);

        @SqlQuery("SELECT *, (SELECT method FROM method WHERE method.id = v.method_id) AS method, " +
                "(SELECT ct.content_type FROM content_type ct WHERE ct.id = v.content_type_id) AS content_type " +
                "FROM visit v WHERE origin_id = ? AND path_id = ? ORDER BY date DESC LIMIT 100")
        List<Visit> list(long originId, long pathId);

        @SqlQuery("SELECT *, (SELECT method FROM method WHERE method.id = v.method_id) AS method, " +
                "(SELECT ct.content_type FROM content_type ct WHERE ct.id = v.content_type_id) AS content_type " +
                "FROM visit v WHERE origin_id = ? AND path_id = ? AND response_payload_digest = ? " +
                "AND revisit_of_date IS NULL LIMIT 1")
        Visit findByResponsePayloadDigest(long originId, long pathId, byte[] digest);

        @SqlUpdate("INSERT INTO visit (origin_id, path_id, date, method_id, status, content_length, " +
                "content_type_id, warc_id, request_position, request_length, request_payload_digest, " +
                "response_position, response_length, response_payload_digest, revisit_of_date) " +
                "VALUES (?, ?, ?, (SELECT id FROM method WHERE method = ?), ?, ?, " +
                "COALESCE((SELECT id FROM content_type WHERE content_type = ?), " +
                "(SELECT id FROM content_type WHERE content_type = 'application/octet-stream')), " +
                "?, ?, ?, ?, ?, ?, ?, ?)")
        void _insert(long originId, long pathId, Instant date, String method, int fetchStatus, long contentLength,
                     String contentType, UUID warcId, long requestPosition, long requestLength, byte[] requestPayloadDigest,
                     long responsePosition, long responseLength, byte[] responsePayloadDigest, Instant revisitOfDate);

        default void insert(Exchange exchange) {
            _insert(exchange.location.originId, exchange.location.pathId, exchange.date, exchange.method,
                            exchange.fetchStatus, exchange.contentLength, exchange.contentType, exchange.warcId,
                            exchange.requestPosition, exchange.requestLength, null, exchange.responsePosition,
                            exchange.responseLength, exchange.digest == null ? null : Arrays.copyOf(exchange.digest, 8),
                            exchange.revisitOf == null ? null : exchange.revisitOf.date);
        }

        @SqlQuery("SELECT date FROM visit WHERE origin_id = ? AND path_id = ? " +
                "AND method_id = (SELECT id FROM method WHERE method = ?) AND STATUS > 0 AND STATUS <> 304 " +
                "ORDER BY ABS(date - ?) DESC LIMIT 1")
        Long _findClosest(long originId, long pathId, String method, Instant closestDate);

        default Visit findClosest(long originId, long pathId, Instant closestDate, String method) {
            Long closest = _findClosest(originId, pathId, method, closestDate);
            return closest == null ? null : find(originId, pathId, Instant.ofEpochMilli(closest));
        }

        @SqlQuery("SELECT v.date, (o.origin || l.path) as url, ct.content_type, v.status, " +
                "v.response_payload_digest, v.response_length, v.response_position, v.warc_id " +
                "FROM visit v " +
                "LEFT JOIN location l ON l.origin_id = v.origin_id AND l.path_id = v.path_id " +
                "LEFT JOIN origin o ON o.id = l.origin_id " +
                "LEFT JOIN content_type ct ON ct.id = v.content_type_id " +
                "WHERE v.origin_id = ? AND v.path_id = ? AND " +
                "v.method_id = (SELECT id FROM method WHERE method = 'GET') AND v.status > 0 AND v.status <> 304 " +
                "AND v.response_payload_digest IS NOT NULL AND v.warc_id IS NOT NULL " +
                "LIMIT 1000")
        List<CdxLine> asCdxLines(long originId, long pathId);

        @SqlQuery("SELECT v.origin_id AS originId, v.path_id AS pathId, v.date, m.method, " +
                "o.origin, l.path, v.status, ct.content_type, v.content_length " +
                "FROM visit v " +
                "LEFT JOIN location l ON l.origin_id = v.origin_id AND l.path_id = v.path_id " +
                "LEFT JOIN origin o ON o.id = v.origin_id " +
                "LEFT JOIN method m ON m.id = v.method_id " +
                "LEFT JOIN content_type ct ON ct.id = v.content_type_id " +
                "LEFT JOIN location_type lt ON lt.id = l.location_type_id " +
                "WHERE v.date < ? " +
                "ORDER BY v.date DESC LIMIT ?")
        @RegisterRowMapper(MapMapper.class)
        List<Map<String, Object>> paginateCrawlLog(Instant after, int limit);

        @SqlQuery("SELECT v.origin_id AS originId, v.path_id AS pathId, v.date, m.method, " +
                "o.origin, l.path, v.status, ct.content_type, v.content_length " +
                "FROM visit v " +
                "LEFT JOIN location l ON l.origin_id = v.origin_id AND l.path_id = v.path_id " +
                "LEFT JOIN origin o ON o.id = v.origin_id " +
                "LEFT JOIN method m ON m.id = v.method_id " +
                "LEFT JOIN content_type ct ON ct.id = v.content_type_id " +
                "LEFT JOIN location_type lt ON lt.id = l.location_type_id " +
                "WHERE v.date < ? AND lt.location_type = 'PAGE' " +
                "ORDER BY v.date DESC LIMIT ?")
        @RegisterRowMapper(MapMapper.class)
        List<Map<String, Object>> paginateCrawlLogPagesOnly(Instant after, int limit);
    }

    @RegisterConstructorMapper(Screenshot.class)
    public interface ScreenshotCacheDAO {
        @SqlUpdate("INSERT INTO screenshot_cache (origin_id, path_id, date, screenshot) VALUES (?, ?, ?, ?)")
        void _insert(long originId, long pathId, Instant date, byte[] screenshot);

        default void insert(Url url, Instant date, byte[] screenshot) {
            _insert(url.originId(), url.pathId(), date, screenshot);
        }

        @SqlUpdate("delete from screenshot_cache where date < (select min(date) from (select date from screenshot_cache order by date desc limit ?))")
        void expire(int keep);

        @SqlQuery("SELECT sc.origin_id, sc.path_id, sc.date, o.origin, l.path, sc.screenshot FROM screenshot_cache sc " +
                "LEFT JOIN location l ON l.origin_id = sc.origin_id AND l.path_id = sc.path_id " +
                "LEFT JOIN origin o ON o.id = sc.origin_id " +
                "WHERE sc.date > ? " +
                "ORDER BY sc.date DESC " +
                "LIMIT ?")
        List<Screenshot> getN(int n, long after);
    }

    public static class Screenshot {
        public final long originId;
        public final long pathId;
        public final Instant date;
        public final Url url;
        public final byte[] screenshot;

        public Screenshot(long originId, long pathId, Instant date, String origin, String path, byte[] screenshot) {
            this.originId = originId;
            this.pathId = pathId;
            this.date = date;
            this.url = new Url(origin + path);
            this.screenshot = screenshot;
        }

        public String screenshotDataUrl() {
            return Util.makeJpegDataUrl(screenshot);
        }
    }

    public static class CdxLine {
        private final Instant date;
        private final String url;
        private final String contentType;
        private final int status;
        private final byte[] payloadDigest;
        private final long length;
        private final long position;
        private final UUID warcId;

        public CdxLine(Instant date, String url, String contentType, int status, byte[] responsePayloadDigest,
                       long responseLength, long responsePosition, UUID warcId) {
            this.date = date;
            this.url = url;
            this.contentType = contentType;
            this.status = status;
            byte[] digest  = responsePayloadDigest;
            this.payloadDigest = digest == null ? null : Arrays.copyOf(digest, 20); // XXX: pad out to match sha1 length
            this.length = responseLength;
            this.position = responsePosition;
            this.warcId = warcId;
        }

        public String toString() {
            String digest = payloadDigest == null ? null : new WarcDigest("sha1", payloadDigest).base32();
            return "- " + Util.ARC_DATE.format(date) + " " + url + " " + contentType + " " + status + " " + (digest == null ? "-" : digest) + " - - "
                    + length + " " + position + " ?id=" + warcId;
        }
    }

    @RegisterConstructorMapper(Webapp.Session.class)
    public interface SessionDAO {
        @SqlQuery("SELECT id, username, role, oidc_state, expiry FROM session WHERE id = ?")
        Optional<Webapp.Session> find(String sessionId);

        @SqlUpdate("INSERT INTO session (id, oidc_state, expiry) VALUES (?, ?, ?)")
        void insert(String id, String oidcState, Instant expiry);

        @SqlUpdate("UPDATE session SET username = :username, role = :role WHERE id = :sessionId")
        void update(String sessionId, String username, String role);

        @SqlUpdate("DELETE FROM session WHERE expiry < ?")
        void _expire(Instant since);

        default void expire() {
            _expire(Instant.now());
        }
    }

    public interface WarcDAO {
        @SqlUpdate("INSERT INTO warc (id, path, created) VALUES (?, ?, ?)")
        void insert(UUID id, String path, Instant created);

        @SqlQuery("SELECT path FROM warc WHERE id = ?")
        String findPath(UUID id);
    }

    public static class IdGenerator {
        private static final long ID_EPOCH = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
        private static final long MAX_TIME = (1L << 41) - 1L;
        private int counter;
        private long prevTimestamp;
        private final int nodeId;

        public IdGenerator(int nodeId) {
            this.nodeId = nodeId;
            if (nodeId < 0 || nodeId > 65535) throw new IllegalArgumentException("expected unsigned 16-bit node id not " + nodeId);
        }

        public long next() {
            long timestamp = System.currentTimeMillis() - ID_EPOCH;
            if (timestamp > MAX_TIME) throw new IllegalStateException("Year 2089 bug. :)");
            long seq;
            synchronized (this) {
                if (timestamp > prevTimestamp) {
                    prevTimestamp = timestamp;
                    counter = 0;
                    seq = 0;
                } else {
                    seq = ++counter;
                }
            }

            if (seq > 64) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return next();
            }

            return (timestamp << 22) | (seq << 16) | nodeId;
        }
    }
}
