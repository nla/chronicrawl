package trickler;

import org.codejargon.fluentjdbc.api.FluentJdbc;
import org.codejargon.fluentjdbc.api.FluentJdbcBuilder;
import org.codejargon.fluentjdbc.api.mapper.ObjectMappers;
import org.codejargon.fluentjdbc.api.query.Query;
import org.codejargon.fluentjdbc.api.query.UpdateResult;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.RunScript;

import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Database implements AutoCloseable {
    private final JdbcConnectionPool jdbcPool;
    private final FluentJdbc fluent;
    private final Query query;
    private final ObjectMappers objectMappers = ObjectMappers.builder().build();

    Database() {
        this("jdbc:h2:file:./data/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE", "sa", "");
    }

    Database(String url, String user, String password) {
        jdbcPool = JdbcConnectionPool.create(url, user, password);
        try (Connection connection = jdbcPool.getConnection()) {
            RunScript.execute(connection, new InputStreamReader(getClass().getResourceAsStream("schema.sql"), UTF_8));
        } catch (SQLException e) {
            throw new RuntimeException("Unable to initialize db", e);
        }
        fluent = new FluentJdbcBuilder().connectionProvider(jdbcPool).build();
        query = fluent.query();
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
        return query.select("SELECT url, type FROM location WHERE next_visit <= ? AND origin_id = ? ORDER BY priority ASC, next_visit ASC LIMIT 1")
                .params(Instant.now(), originId)
                .firstResult(rs -> new Location(new Url(rs.getString("url")),
                        LocationType.valueOf(rs.getString("type"))))
                .orElse(null);
    }

    public void updateLocationVisit(long id, Instant lastVisit, Instant nextVisit) {
        check(query.update("UPDATE location SET next_visit = ?, last_visit = ? WHERE id = ?").params(nextVisit, lastVisit, id).run());
    }

    public void updateOriginVisit(long originId, Instant lastVisit, Instant nextVisit) {
        check(query.update("UPDATE origin SET next_visit = ?, last_visit = ? WHERE id = ?").params(nextVisit, lastVisit, originId).run());
    }

    private void check(UpdateResult result) {
        if (result.affectedRows() == 0) {
            throw new IllegalArgumentException("update failed");
        }
    }

    public void updateOriginRobotsCrawlDelay(long originId, Short crawlDelay) {
        check(query.update("UPDATE origin SET robots_crawl_delay = ? WHERE id = ?").params(crawlDelay, originId).run());
    }

    public Origin selectNextOrigin() {
        return query.select("SELECT id, name, robots_crawl_delay, next_visit FROM origin ORDER BY next_visit ASC LIMIT 1")
                .firstResult(rs -> new Origin(rs.getLong("id"),
                        rs.getString("name"),
                        getLongOrNull(rs, "robots_crawl_delay"),
                        rs.getTimestamp("next_visit").toInstant())).orElse(null);
    }

    public static Long getLongOrNull(ResultSet rs, String field) throws SQLException {
        long value = rs.getLong(field);
        return rs.wasNull() ? null : value;
    }

}
