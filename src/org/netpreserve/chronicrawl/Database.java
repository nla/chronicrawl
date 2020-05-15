package org.netpreserve.chronicrawl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.FluentJdbcSqlException;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Query;
import org.codejargon.fluentjdbc.api.query.UpdateResult;
import org.netpreserve.jwarc.WarcDigest;
import org.sqlite.SQLiteConfig;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.*;

import static java.util.Objects.requireNonNull;

public class Database implements AutoCloseable {
    private final HikariDataSource dataSource;
    final Query query;
    public final LocationDAO locations = new LocationDAO();
    public final OriginDAO origins = new OriginDAO();
    public final ScreenshotCacheDAO screenshotCache = new ScreenshotCacheDAO();
    public final SitemapEntryDAO sitemapEntries = new SitemapEntryDAO();
    public final SessionDAO sessions = new SessionDAO();
    public final VisitDAO visits = new VisitDAO();
    public final WarcDAO warcs = new WarcDAO();

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
        FluentJdbc fluent = new FluentJdbcBuilder().connectionProvider(dataSource).build();

        query = fluent.query();
    }

    public static UUID getUUID(ResultSet rs, String field) throws SQLException {
        byte[] bytes = rs.getBytes(field);
        if (bytes == null) return null;
        var bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
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

    public class OriginDAO {
        private static final String fields = "o.id, o.origin, o.discovered, o.last_visit, o.next_visit, " +
                "o.robots_crawl_delay, o.robots_txt, (SELECT cp.name FROM crawl_policy cp WHERE cp.id = o.crawl_policy_id) as crawl_policy";

        public Origin find(long originId) {
            return query.select("SELECT " + fields + " FROM origin o WHERE id = ?")
                    .params(originId).firstResult(Origin::new).orElse(null);
        }

        public List<Origin> peek(int limit) {
            return query.select("SELECT " + fields + " FROM origin o " +
                    "WHERE crawl_policy_id = (SELECT id FROM crawl_policy WHERE name = 'CONTINUOUS') AND next_visit IS NOT NULL " +
                    "ORDER BY next_visit ASC LIMIT ?")
                    .params(limit)
                    .listResult(Origin::new);
        }

        private Optional<String> findOrigin(long id) {
            return query.select("SELECT origin FROM origin WHERE id = ?").params(id).firstResult(Mappers.singleString());
        }

        public boolean tryInsert(long id, String name, Instant discovered, CrawlPolicy crawlPolicy) {
                Optional<String> existing = findOrigin(id);
                if (existing.isEmpty()) {
                    try {
                        query.update("INSERT INTO origin (id, origin, discovered, next_visit, crawl_policy_id) VALUES (?, ?, ?, ?, (SELECT id FROM crawl_policy WHERE name = ?))")
                                .params(id, name, discovered.toEpochMilli(), discovered.toEpochMilli(), crawlPolicy).run();
                    } catch (FluentJdbcSqlException e) {
                        if (!(e.getCause() instanceof SQLIntegrityConstraintViolationException || e.getCause().getMessage().contains("SQLITE_CONSTRAINT"))) throw e;
                        existing = findOrigin(id); // handle insert race
                    }
                }
                if (existing.isPresent() && !existing.get().equals(name)) {
                    throw new RuntimeException("Hash collision between " + existing.get() + " and " + name);
                }
                return existing.isEmpty();
        }

        public void updateVisit(long originId, Instant lastVisit, Instant nextVisit) {
            check(query.update("UPDATE origin SET next_visit = ?, last_visit = ? WHERE id = ?")
                    .params(nextVisit == null ? null : nextVisit.toEpochMilli(), lastVisit.toEpochMilli(), originId).run());
        }

        public void updateRobots(long originId, Short crawlDelay, byte[] robotsTxt) {
            check(query.update("UPDATE origin SET robots_crawl_delay = ?, robots_txt = ? WHERE id = ?")
                    .params(crawlDelay, robotsTxt, originId).run());
        }

        public void updateCrawlPolicy(long id, CrawlPolicy crawlPolicy) {
            check(query.update("UPDATE origin SET crawl_policy_id = (SELECT id FROM crawl_policy WHERE name = ?) WHERE id = ?").params(crawlPolicy, id).run());
        }
    }

    public class LocationDAO {
        private static final String fields = "l.origin_id, l.path_id, o.origin, l.path, l.depth, " +
                "(SELECT lt.location_type FROM location_type lt WHERE lt.id = l.location_type_id) AS location_type, " +
                "l.via_origin_id, l.via_path_id, l.discovered, l.last_visit, l.next_visit";

        public Location find(long originId, long pathId) {
            return query.select("SELECT " + fields + " FROM location l " +
                    "LEFT JOIN origin o ON o.id = l.origin_id " +
                    "WHERE origin_id = ? AND path_id = ? LIMIT 1")
                    .params(originId, pathId)
                    .firstResult(Location::new)
                    .orElse(null);
        }



        public void tryInsert(Url url, Location.Type type, Url viaUrl, int depth, Instant discovered) {
            do {
                var existingPath = findPath(url);
                if (existingPath.isEmpty()) {
                    try {
                        query.update("INSERT INTO location (origin_id, path_id, path, location_type_id, depth, via_origin_id, via_path_id, discovered, next_visit) " +
                                "VALUES (?, ?, ?, (SELECT id FROM location_type WHERE location_type = ?), ?, ?, ?, ?, ?)")
                                .params(url.originId(), url.pathId(), url.pathref(), type.name(), depth,
                                        viaUrl == null ? null : viaUrl.originId(), viaUrl == null ? null : viaUrl.pathId(),
                                        discovered.toEpochMilli(), discovered.toEpochMilli()).run();
                    } catch (FluentJdbcSqlException e) {
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

        private Optional<String> findPath(Url url) {
            return query.select("SELECT path FROM location WHERE origin_id = ? AND path_id = ?").params(url.originId(), url.pathId()).firstResult(Mappers.singleString());
        }

        public Location peek(long originId) {
            return peek(originId, 1).stream().findFirst().orElse(null);
        }

        public List<Location> peek(long originId, int limit) {
            return query.select("SELECT " + fields + " FROM location l " +
                    "LEFT JOIN origin o ON o.id = l.origin_id " +
                    "LEFT JOIN sitemap_entry se ON se.origin_id = l.origin_id AND se.path_id = l.path_id " +
                    "WHERE l.next_visit <= ? AND l.origin_id = ? " +
                    "ORDER BY l.location_type_id DESC, se.priority DESC, l.depth ASC, l.next_visit ASC LIMIT ?")
                    .params(Instant.now().toEpochMilli(), originId, limit)
                    .listResult(Location::new);
        }

        public void updateVisitData(long originId, long pathId, Instant lastVisit, Instant nextVisit) {
            check(query.update("UPDATE location SET next_visit = ?, last_visit = ? WHERE origin_id = ? AND path_id = ?")
                    .params(nextVisit.toEpochMilli(), lastVisit.toEpochMilli(), originId, pathId).run());
        }
    }

    public class SitemapEntryDAO {
        public void insertOrReplace(Url url, Url sitemapUrl, Sitemap.ChangeFreq changeFreq, Float priority, String lastmod) {
            query.update("DELETE FROM sitemap_entry WHERE origin_id = ? AND path_id = ?").params(url.originId(), url.pathId()).run();
            check(query.update("INSERT INTO sitemap_entry (origin_id, path_id, sitemap_origin_id, sitemap_path_id, changefreq, priority, lastmod) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(url.originId(), url.pathId(), sitemapUrl.originId(), sitemapUrl.pathId(), changeFreq, priority, lastmod).run());
        }
    }

    public class VisitDAO {
        public Visit find(long originId, long pathId, Instant date) {
            return query.select("SELECT *, (SELECT method FROM method WHERE method.id = v.method_id) AS method, " +
                    "(SELECT ct.content_type FROM content_type ct WHERE ct.id = v.content_type_id) AS content_type " +
                    "FROM visit v WHERE origin_id = ? AND path_id = ? AND date = ?").params(originId, pathId, date.toEpochMilli())
                    .singleResult(Visit::new);
        }

        public List<Visit> list(long originId, long pathId) {
            return query.select("SELECT *, (SELECT method FROM method WHERE method.id = v.method_id) AS method, " +
                    "(SELECT ct.content_type FROM content_type ct WHERE ct.id = v.content_type_id) AS content_type " +
                    "FROM visit v WHERE origin_id = ? AND path_id = ? ORDER BY date DESC LIMIT 100")
                    .params(originId, pathId)
                    .listResult(Visit::new);
        }

        public Visit findByResponsePayloadDigest(long originId, long pathId, byte[] digest) {
            Optional<Long> date = query.select("SELECT date FROM visit WHERE origin_id = ? AND path_id = ? AND response_payload_digest = ? AND revisit_of_date IS NULL LIMIT 1")
                    .params(originId, pathId, Arrays.copyOf(digest, 8)).firstResult(Mappers.singleLong());
            if (date.isEmpty()) return null;
            return find(originId, pathId, Instant.ofEpochMilli(date.get()));
        }

        public void insert(Exchange exchange) {
            query.update("INSERT INTO visit (origin_id, path_id, date, method_id, status, content_length, " +
                    "content_type_id, warc_id, request_position, request_length, request_payload_digest, " +
                    "response_position, response_length, response_payload_digest, revisit_of_date) " +
                    "VALUES (?, ?, ?, (SELECT id FROM method WHERE method = ?), ?, ?, " +
                    "COALESCE((SELECT id FROM content_type WHERE content_type = ?), (SELECT id FROM content_type WHERE content_type = 'application/octet-stream')), " +
                    "?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(exchange.location.originId, exchange.location.pathId, exchange.date.toEpochMilli(), exchange.method,
                            exchange.fetchStatus, exchange.contentLength, exchange.contentType, toBytes(exchange.warcId),
                            exchange.requestPosition, exchange.requestLength, null, exchange.responsePosition,
                            exchange.responseLength, exchange.digest == null ? null : Arrays.copyOf(exchange.digest, 8),
                            exchange.revisitOf == null ? null : exchange.revisitOf.date.toEpochMilli()).run();
        }

        public Visit findClosest(long originId, long pathId, Instant closestDate, String method) {
            Optional<Long> closest = query.select("SELECT date FROM visit WHERE origin_id = ? AND path_id = ? AND method_id = (SELECT id FROM method WHERE method = ?) " +
                    "ORDER BY ABS(date - ?) DESC LIMIT 1")
                    .params(originId, pathId, method, closestDate.toEpochMilli()).firstResult(Mappers.singleLong());
            return closest.map(date -> find(originId, pathId, Instant.ofEpochMilli(date))).orElse(null);
        }

        public List<CdxLine> asCdxLines(long originId, long pathId) {
            return query.select("SELECT v.date, (o.origin || l.path) as url, ct.content_type, v.status, " +
                    "v.response_payload_digest, v.response_length, v.response_position, v.warc_id " +
                    "FROM visit v " +
                    "LEFT JOIN location l ON l.origin_id = v.origin_id AND l.path_id = v.path_id " +
                    "LEFT JOIN origin o ON o.id = l.origin_id " +
                    "LEFT JOIN content_type ct ON ct.id = v.content_type_id " +
                    "WHERE v.origin_id = ? AND v.path_id = ? AND v.method_id = (SELECT id FROM method WHERE method = 'GET') " +
                    "AND v.response_payload_digest IS NOT NULL AND v.warc_id IS NOT NULL " +
                    "LIMIT 1000").params(originId, pathId)
                    .listResult(CdxLine::new);
        }
    }

    public class ScreenshotCacheDAO {
        public void insert(Url url, Instant date, byte[] screenshot) {
            query.update("INSERT INTO screenshot_cache (origin_id, path_id, date, screenshot) VALUES (?, ?, ?, ?)").params(url.originId(), url.pathId(), date.toEpochMilli(), screenshot).run();
        }

        public void expire(int keep) {
            query.update("delete from screenshot_cache where date < (select min(date) from (select date from screenshot_cache order by date desc limit ?))").params(keep).run();
        }

        public List<Screenshot> getN(int n, long after) {
            return query.select("SELECT sc.origin_id, sc.path_id, sc.date, o.origin, l.path, sc.screenshot FROM screenshot_cache sc " +
                    "LEFT JOIN location l ON l.origin_id = sc.origin_id AND l.path_id = sc.path_id " +
                    "LEFT JOIN origin o ON o.id = sc.origin_id " +
                    "WHERE sc.date > ? " +
                    "ORDER BY sc.date DESC " +
                    "LIMIT ?").params(after, n).listResult(Screenshot::new);
        }
    }

    public static class Screenshot {
        public final long originId;
        public final long pathId;
        public final Instant date;
        public final Url url;
        public final byte[] screenshot;

        public Screenshot(ResultSet rs) throws SQLException {
            this.originId = rs.getLong("origin_id");
            this.pathId = rs.getLong("path_id");
            this.date = Instant.ofEpochMilli(rs.getLong("date"));
            this.url = new Url(rs.getString("origin") + rs.getString("path"));
            this.screenshot = rs.getBytes("screenshot");
        }

        public String screenshotDataUrl() {
            return Util.makeJpegDataUrl(screenshot);
        }
    }

    public class CdxLine {
        private final Instant date;
        private final String url;
        private final String contentType;
        private final int status;
        private final byte[] payloadDigest;
        private final long length;
        private final long position;
        private final UUID warcId;

        CdxLine(ResultSet rs) throws SQLException {
            date = getInstant(rs, "date");
            url = rs.getString("url");
            contentType = rs.getString("content_type");
            status = rs.getInt("status");
            byte[] digest = rs.getBytes("response_payload_digest");
            payloadDigest = digest == null ? null : Arrays.copyOf(digest, 20); // XXX: pad out to match sha1 length
            length = rs.getLong("response_length");
            position = rs.getLong("response_position");
            warcId = getUUID(rs, "warc_id");
        }

        public String toString() {
            String digest = payloadDigest == null ? null : new WarcDigest("sha1", payloadDigest).base32();
            return "- " + Util.ARC_DATE.format(date) + " " + url + " " + contentType + " " + status + " " + (digest == null ? "-" : digest) + " - - "
                    + length + " " + position + " ?id=" + warcId;
        }
    }

    public class SessionDAO {
        public Optional<Webapp.Session> find(String sessionId) {
            return query.select("SELECT id, username, role, oidc_state, expiry FROM session WHERE id = ?").params(sessionId)
                    .firstResult((ResultSet rs) -> new Webapp.Session(
                            rs.getString("id"),
                            rs.getString("username"),
                            rs.getString("role"),
                            rs.getString("oidc_state"),
                            getInstant(rs, "expiry")));
        }

        public void insert(String id, String oidcState, Instant expiry) {
            query.update("INSERT INTO session (id, oidc_state, expiry) VALUES (?, ?, ?)")
                    .params(id, oidcState, expiry).run();
        }

        public void update(String sessionId, String username, String role) {
            check(query.update("UPDATE session SET username = ?, role = ? WHERE id = ?").params(username, role, sessionId).run());
        }

        public void expire() {
            query.update("DELETE FROM session WHERE expiry < ?").params(Instant.now()).run();
        }
    }

    public class WarcDAO {
        public void insert(UUID id, String path, Instant created) {
            query.update("INSERT INTO warc (id, path, created) VALUES (?, ?, ?)").params(toBytes(id), path, created.toEpochMilli()).run();
        }

        public String findPath(UUID id) {
            return query.select("SELECT path FROM warc WHERE id = ?").params(toBytes(requireNonNull(id))).singleResult(Mappers.singleString());
        }
    }

    private void check(UpdateResult result) {
        if (result.affectedRows() == 0) {
            throw new IllegalArgumentException("update failed");
        }
    }

    public static Long getLongOrNull(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field);
        return rs.wasNull() ? null : value;
    }

    public static Instant getInstant(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    public List<Map<String, Object>> paginateCrawlLog(Instant after, boolean pagesOnly, int limit) {
        return query.select("SELECT v.origin_id AS originId, v.path_id AS pathId, v.date, m.method, " +
                "o.origin, l.path, v.status, ct.content_type, v.content_length " +
                "FROM visit v " +
                "LEFT JOIN location l ON l.origin_id = v.origin_id AND l.path_id = v.path_id " +
                "LEFT JOIN origin o ON o.id = v.origin_id " +
                "LEFT JOIN method m ON m.id = v.method_id " +
                "LEFT JOIN content_type ct ON ct.id = v.content_type_id " +
                "LEFT JOIN location_type lt ON lt.id = l.location_type_id " +
                "WHERE v.date < ? " + (pagesOnly ? " AND lt.location_type = 'PAGE' " : "") +
                "ORDER BY v.date DESC LIMIT ?")
                .params(after, limit)
                .listResult(Mappers.map());
    }
}
