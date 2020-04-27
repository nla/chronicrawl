package trickler;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
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
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Database implements AutoCloseable {
    private final JdbcConnectionPool jdbcPool;
    private final FluentJdbc fluent;
    private final Query query;
    private final ObjectMappers objectMappers = ObjectMappers.builder().build();

    private final static String locationFields = "url, type, etag, last_modified, via";
    private final static Mapper<Location> locationMapper = rs -> new Location(new Url(rs.getString("url")),
            LocationType.valueOf(rs.getString("type")),
            rs.getString("etag"),
            getInstant(rs, "last_modified"),
            getLongOrNull(rs, "via"));

    Database() {
        this("jdbc:h2:file:./data/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE", "sa", "");
    }

    Database(String url, String user, String password) {
        jdbcPool = JdbcConnectionPool.create(url, user, password);
        init();
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

    public void tryInsertLocation(Url url, LocationType type, long via, Instant discovered, int priority) {
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

    public void updateLocationVisit(long id, Instant lastVisit, Instant nextVisit, String etag, Instant lastModified) {
        check(query.update("UPDATE location SET next_visit = ?, last_visit = ?, etag = ?, last_modified = ? WHERE id = ?").params(nextVisit, lastVisit, etag, lastModified, id).run());
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
        return query.select("SELECT id, name, robots_crawl_delay, next_visit, robots_txt FROM origin ORDER BY next_visit ASC LIMIT 1")
                .firstResult(rs -> new Origin(rs.getLong("id"),
                        rs.getString("name"),
                        getLongOrNull(rs, "robots_crawl_delay"),
                        rs.getTimestamp("next_visit").toInstant(),
                        rs.getBytes("robots_txt"))).orElse(null);
    }

    public static Long getLongOrNull(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field);
        return rs.wasNull() ? null : value;
    }

    public static Instant getInstant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    public void insertRecord(UUID id, long position) {
        query.update("INSERT INTO record (id, position) VALUES (?, ?)").params(id, position).run();
    }

    public void insertVisit(long locationId, Instant date, int status, Long contentLength, String contentType, Long requestOffset, Long responseOffset) {
        query.update("INSERT INTO visit (location_id, date, status, content_length, content_type, request_offset, response_offset) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(locationId, date, status, contentLength, contentType, requestOffset, responseOffset).run();
    }

    public void tryInsertLink(long src, long dst) {
        query.update("INSERT IGNORE INTO link (src, dst) VALUES (?, ?)").params(src, dst).run();
    }
}
