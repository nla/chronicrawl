package trickler;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.mapper.Mappers;
import org.codejargon.fluentjdbc.api.mapper.ObjectMappers;
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
    private final FluentJdbc fluent;
    private final Query query;
    private final ObjectMappers objectMappers = ObjectMappers.builder().build();

    private final static String locationFields = "url, type, etag, last_modified, via, etag_response_id, etag_date";
    private final static Mapper<Location> locationMapper = rs -> new Location(new Url(rs.getString("url")),
            Location.Type.valueOf(rs.getString("type")),
            rs.getString("etag"),
            getInstant(rs, "last_modified"),
            getLongOrNull(rs, "via"),
            rs.getObject("etag_response_id", UUID.class),
            getInstant(rs, "etag_date"));
    private final static String originFields = "id, name, robots_crawl_delay, next_visit, robots_txt, crawl_policy";
    private final static Mapper<Origin> originMapper = rs -> new Origin(rs.getLong("id"),
            rs.getString("name"),
            getLongOrNull(rs, "robots_crawl_delay"),
            getInstant(rs, "next_visit"),
            rs.getBytes("robots_txt"),
            CrawlPolicy.valueOfOrNull(rs.getString("crawl_policy")));

    Database() {
        this("jdbc:h2:file:./data/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE", "sa", "");
    }

    Database(String url, String user, String password) {
        jdbcPool = JdbcConnectionPool.create(url, user, password);
        fluent = new FluentJdbcBuilder().connectionProvider(jdbcPool).build();
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

    public boolean tryInsertOrigin(long id, String name, Instant discovered) {
        return query.update("INSERT IGNORE INTO origin (id, name, discovered, next_visit) VALUES (?, ?, ?, ?)").params(id, name, discovered, discovered).run().affectedRows() > 0;
    }

    public void tryInsertLocation(Url url, Location.Type type, long via, Instant discovered, int priority) {
        System.out.println("Insert " + url);
        query.update("INSERT IGNORE INTO location (id, origin_id, via, type, url, discovered, next_visit, priority) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(url.id(), url.originId(), via, type, url.toString(), discovered, discovered, priority).run();
    }

    public void updateLocationSitemapData(long locationId, Sitemap.ChangeFreq changeFreq, Float priority, String lastmod) {
        check(query.update("UPDATE location SET sitemap_changefreq = ?, sitemap_priority = ?, sitemap_lastmod = ? WHERE id = ?")
                .params(changeFreq, priority, lastmod, locationId).run());
    }

    public Location selectNextLocation(long originId) {
        return query.select("SELECT " + locationFields + " FROM location WHERE next_visit <= ? AND origin_id = ? ORDER BY priority ASC, sitemap_priority DESC, next_visit ASC LIMIT 1")
                .params(Instant.now(), originId)
                .firstResult(locationMapper)
                .orElse(null);
    }

    public Location selectLocationById(long locationId) {
        return query.select("SELECT " + locationFields + " FROM location WHERE id = ? LIMIT 1")
                .params(locationId)
                .firstResult(locationMapper)
                .orElse(null);
    }

    public UUID selectLastResponseRecordId(String method, long locationId) {
        return query.select("SELECT response_id FROM visit WHERE method = ? AND location_id = ? ORDER BY date DESC LIMIT 1")
                .params(method, locationId)
                .firstResult(rs -> (UUID)rs.getObject("response_id"))
                .orElse(null);
    }

    public void updateLocationVisit(long id, Instant lastVisit, Instant nextVisit) {
        check(query.update("UPDATE location SET next_visit = ?, last_visit = ? WHERE id = ?").params(nextVisit, lastVisit, id).run());
    }

    public void updateLocationEtag(long id, String etag, Instant lastModified, UUID etagResponseId, Instant etagDate) {
        check(query.update("UPDATE location SET etag = ?, last_modified = ?, etag_response_id = ? WHERE id = ?").params(etag, lastModified, etagResponseId, id).run());
    }


    public void updateOriginVisit(long originId, Instant lastVisit, Instant nextVisit) {
        check(query.update("UPDATE origin SET next_visit = ?, last_visit = ? WHERE id = ?").params(nextVisit, lastVisit, originId).run());
    }

    private void check(UpdateResult result) {
        if (result.affectedRows() == 0) {
            throw new IllegalArgumentException("update failed");
        }
    }

    public void updateOriginRobots(long originId, Short crawlDelay, byte[] robotsTxt) {
        check(query.update("UPDATE origin SET robots_crawl_delay = ?, robots_txt = ? WHERE id = ?")
                .params(crawlDelay, robotsTxt, originId).run());
    }

    public Origin selectNextOrigin() {
        return query.select("SELECT " + originFields + " FROM origin " +
                "WHERE (crawl_policy IS NULL OR crawl_policy = 'CONTINUOUS') AND next_visit IS NOT NULL " +
                "ORDER BY next_visit ASC LIMIT 1")
                .firstResult(originMapper).orElse(null);
    }

    public Origin selectOriginById(long originId) {
        return query.select("SELECT " + originFields + " FROM origin WHERE id = ?")
                .params(originId).firstResult(originMapper).orElse(null);
    }

    public static Long getLongOrNull(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field);
        return rs.wasNull() ? null : value;
    }

    public static Instant getInstant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    public void insertVisit(String method, long locationId, Instant date, int status, Long contentLength, String contentType, UUID responseId) {
        query.update("INSERT INTO visit (method, location_id, date, status, content_length, content_type, response_id) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(method, locationId, date, status, contentLength, contentType, responseId).run();
    }

    public void tryInsertLink(long src, long dst) {
        query.update("INSERT IGNORE INTO link (src, dst) VALUES (?, ?)").params(src, dst).run();
    }

    public void insertWarc(UUID id, String path, Instant created) {
        query.update("INSERT INTO warc (id, path, created) VALUES (?, ?, ?)").params(id, path, created).run();
    }

    public void insertRecord(UUID id, long locationId, Instant date, String type, UUID warcId, long position, byte[] payloadDigest) {
        query.update("INSERT INTO record (id, location_id, date, type, warc_id, position, payload_digest) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, locationId, date, type, warcId, position, payloadDigest).run();
    }

    public RecordLocation locateRecord(UUID recordId) {
        return query.select("SELECT warc.path, record.position FROM record " +
                "LEFT JOIN warc ON warc.id = record.warc_id " +
                "WHERE record.id = ?").params(recordId).singleResult(RecordLocation::new);
    }

    public List<Map<String, Object>> paginateCrawlLog(Instant after, boolean pagesOnly, int limit) {
        return query.select("SELECT v.date, v.method, l.id location_id, l.url, v.status, v.content_type, " +
                "v.content_length, v.response_id " +
                "FROM visit v " +
                "LEFT JOIN location l ON l.id = v.location_id " +
                "WHERE v.date < ? " + (pagesOnly ? " AND l.type = 'PAGE' " : "") +
                "ORDER BY date DESC LIMIT ?")
                .params(after, limit)
                .listResult(Mappers.map());
    }

    public List<Map<String, Object>>  visitsForLocation(long locationId) {
        return query.select("SELECT * FROM visit WHERE location_id = ? ORDER BY date DESC LIMIT 100")
                .params(locationId)
                .listResult(Mappers.map());
    }

    public Optional<IdDate> findResponseWithPayloadDigest(long locationId, byte[] payloadDigest) {
        return query.select("SELECT * FROM record WHERE location_id = ? AND type = 'response' AND payload_digest = ? ORDER BY date DESC LIMIT 1")
                .params(locationId, payloadDigest).firstResult(IdDate::new);
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
