package org.netpreserve.pagedrover;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.query.Mapper;
import org.codejargon.fluentjdbc.api.query.Query;
import org.codejargon.fluentjdbc.api.query.UpdateResult;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.RunScript;

import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Database implements AutoCloseable {
    private final JdbcConnectionPool jdbcPool;
    private final Query query;
    public final LocationDAO locations = new LocationDAO();
    public final OriginDAO origins = new OriginDAO();
    public final RecordDAO records = new RecordDAO();
    public final SessionDAO sessions = new SessionDAO();
    public final VisitDAO visits = new VisitDAO();
    public final WarcDAO warcs = new WarcDAO();

    Database() {
        this("jdbc:h2:file:./data/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE", "sa", "");
    }

    Database(String url, String user, String password) {
        jdbcPool = JdbcConnectionPool.create(url, user, password);
        FluentJdbc fluent = new FluentJdbcBuilder().connectionProvider(jdbcPool).build();
        query = fluent.query();
    }

    void init() {
        try (Connection connection = jdbcPool.getConnection()) {
            RunScript.execute(connection, new InputStreamReader(getClass().getResourceAsStream("schema.sql"), UTF_8));
        } catch (SQLException e) {
            throw new RuntimeException("Unable to initialize db", e);
        }
    }

    @Override
    public void close() {
        jdbcPool.dispose();
    }

    public class OriginDAO {
        private static final String fields = "id, name, robots_crawl_delay, next_visit, robots_txt, crawl_policy";
        private final Mapper<Origin> mapper = rs -> new Origin(rs.getLong("id"),
                rs.getString("name"),
                getLongOrNull(rs, "robots_crawl_delay"),
                getInstant(rs, "next_visit"),
                rs.getBytes("robots_txt"),
                CrawlPolicy.valueOfOrNull(rs.getString("crawl_policy")));

        public Origin find(long originId) {
            return query.select("SELECT " + fields + " FROM origin WHERE id = ?")
                    .params(originId).firstResult(mapper).orElse(null);
        }

        public Origin next() {
            return query.select("SELECT " + fields + " FROM origin " +
                    "WHERE (crawl_policy IS NULL OR crawl_policy = 'CONTINUOUS') AND next_visit IS NOT NULL " +
                    "ORDER BY next_visit ASC LIMIT 1")
                    .firstResult(mapper).orElse(null);
        }

        public boolean tryInsert(long id, String name, Instant discovered) {
            return query.update("INSERT IGNORE INTO origin (id, name, discovered, next_visit) VALUES (?, ?, ?, ?)").params(id, name, discovered, discovered).run().affectedRows() > 0;
        }

        public void updateVisit(long originId, Instant lastVisit, Instant nextVisit) {
            check(query.update("UPDATE origin SET next_visit = ?, last_visit = ? WHERE id = ?").params(nextVisit, lastVisit, originId).run());
        }

        public void updateRobots(long originId, Short crawlDelay, byte[] robotsTxt) {
            check(query.update("UPDATE origin SET robots_crawl_delay = ?, robots_txt = ? WHERE id = ?")
                    .params(crawlDelay, robotsTxt, originId).run());
        }
    }

    public class LocationDAO {
        private static final String fields = "url, type, etag, last_modified, via, etag_response_id, etag_date";
        private final Mapper<Location> mapper = rs -> new Location(new Url(rs.getString("url")),
                Location.Type.valueOf(rs.getString("type")),
                rs.getString("etag"),
                getInstant(rs, "last_modified"),
                getLongOrNull(rs, "via"),
                rs.getObject("etag_response_id", UUID.class),
                getInstant(rs, "etag_date"));

        public Location find(long locationId) {
            return query.select("SELECT " + fields + " FROM location WHERE id = ? LIMIT 1")
                    .params(locationId)
                    .firstResult(mapper)
                    .orElse(null);
        }

        public void tryInsert(Url url, Location.Type type, long via, Instant discovered, int priority) {
            query.update("INSERT IGNORE INTO location (id, origin_id, via, type, url, discovered, next_visit, priority) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(url.id(), url.originId(), via, type, url.toString(), discovered, discovered, priority).run();
        }

        public Location next(long originId) {
            return query.select("SELECT " + fields + " FROM location WHERE next_visit <= ? AND origin_id = ? ORDER BY priority ASC, sitemap_priority DESC, next_visit ASC LIMIT 1")
                    .params(Instant.now(), originId)
                    .firstResult(mapper)
                    .orElse(null);
        }

        public void updateSitemapData(long locationId, Sitemap.ChangeFreq changeFreq, Float priority, String lastmod) {
            check(query.update("UPDATE location SET sitemap_changefreq = ?, sitemap_priority = ?, sitemap_lastmod = ? WHERE id = ?")
                    .params(changeFreq, priority, lastmod, locationId).run());
        }

        public void updateVisitData(long id, Instant lastVisit, Instant nextVisit) {
            check(query.update("UPDATE location SET next_visit = ?, last_visit = ? WHERE id = ?").params(nextVisit, lastVisit, id).run());
        }

        public void updateEtagData(long id, String etag, Instant lastModified, UUID etagResponseId, Instant etagDate) {
            check(query.update("UPDATE location SET etag = ?, last_modified = ?, etag_response_id = ?, etag_date = ? WHERE id = ?").params(etag, lastModified, etagResponseId, etagDate, id).run());
        }
    }

    public class VisitDAO {
        public Map<String, Object> find(UUID id) {
            return query.select("SELECT * FROM visit WHERE id = ?").params(id).singleResult(Mappers.map());
        }

        public List<Map<String, Object>> findByLocationId(long locationId) {
            return query.select("SELECT * FROM visit WHERE location_id = ? ORDER BY date DESC LIMIT 100")
                    .params(locationId)
                    .listResult(Mappers.map());
        }

        public void insert(UUID id, String method, long locationId, Instant date, int status, Long contentLength, String contentType) {
            query.update("INSERT INTO visit (id, method, location_id, date, status, content_length, content_type) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(id, method, locationId, date, status, contentLength, contentType).run();
        }
    }

    public class RecordDAO {
        public UUID lastResponseId(String method, long locationId) {
            return query.select("SELECT r.id FROM record r " +
                    "LEFT JOIN visit v ON v.id = r.visit_ID " +
                    "WHERE v.method = ? AND v.location_id = ? AND r.type = 'response' " +
                    "ORDER BY v.date DESC LIMIT 1")
                    .params(method, locationId)
                    .firstResult(rs -> (UUID)rs.getObject("id"))
                    .orElse(null);
        }

        public void insert(UUID id, UUID visitId, String type, UUID warcId, long position, long length, byte[] payloadDigest) {
            query.update("INSERT INTO record (id, visit_id, type, warc_id, position, length, payload_digest) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(id, visitId, type, warcId, position, length, payloadDigest).run();
        }

        public RecordLocation locate(UUID recordId) {
            return query.select("SELECT warc.path, record.position FROM record " +
                    "LEFT JOIN warc ON warc.id = record.warc_id " +
                    "WHERE record.id = ?").params(recordId).singleResult(RecordLocation::new);
        }

        public Optional<IdDate> findResponseByPayloadDigest(long locationId, byte[] payloadDigest) {
            return query.select("SELECT * FROM record r " +
                    "LEFT JOIN visit v ON v.id = r.visit_id " +
                    "WHERE location_id = ? AND type = 'response' AND payload_digest = ? ORDER BY date DESC LIMIT 1")
                    .params(locationId, payloadDigest).firstResult(IdDate::new);
        }

        public List<Map<String, Object>> findByVisitId(UUID visitId) {
            return query.select("SELECT * FROM record WHERE visit_id = ?").params(visitId).listResult(Mappers.map());
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
            query.update("INSERT INTO warc (id, path, created) VALUES (?, ?, ?)").params(id, path, created).run();
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
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    public void tryInsertLink(long src, long dst) {
        query.update("INSERT IGNORE INTO link (src, dst) VALUES (?, ?)").params(src, dst).run();
    }

    public List<Map<String, Object>> paginateCrawlLog(Instant after, boolean pagesOnly, int limit) {
        return query.select("SELECT v.date, v.method, l.id location_id, l.url, v.status, v.content_type, " +
                "v.content_length " +
                "FROM visit v " +
                "LEFT JOIN location l ON l.id = v.location_id " +
                "WHERE v.date < ? " + (pagesOnly ? " AND l.type = 'PAGE' " : "") +
                "ORDER BY date DESC LIMIT ?")
                .params(after, limit)
                .listResult(Mappers.map());
    }

    static class IdDate {
        final UUID id;
        final Instant date;

        IdDate(ResultSet rs) throws SQLException {
            id = rs.getObject("id", UUID.class);
            date = rs.getObject("date", Instant.class);
        }
    }

    static class RecordLocation {
        final String path;
        final long position;

        RecordLocation(ResultSet rs) throws SQLException {
            this.path = rs.getString("path");
            this.position = rs.getLong("position");
        }
    }
}
