package org.netpreserve.chronicrawl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class Rule {
    public final long id;
    public final long originId;
    public final String pathRegex;
    public final Pattern pathRegexCompiled;
    public final Long scheduleId;
    public final String scheduleName;

    public Rule(ResultSet rs) throws SQLException {
        id = rs.getLong("id");
        originId = rs.getLong("origin_id");
        pathRegex = rs.getString("path_regex");
        pathRegexCompiled = Pattern.compile(pathRegex);
        scheduleId = Database.getLongOrNull(rs, "schedule_id");
        scheduleName = rs.getString("schedule_name");
    }

    public boolean matches(String pathref) {
        return pathRegexCompiled.matcher(pathref).matches();
    }

    public static Rule bestMatching(List<Rule> rules, Location location) {
        Rule best = null;
        String pathref = location.url.pathref();
        for (Rule rule : rules) {
            if (rule.matches(pathref) && (best == null || best.pathRegex.length() < rule.pathRegex.length())) {
                best = rule;
            }
        }
        return best;
    }

    public static void reapplyRulesToOrigin(Database db, long originId) {
        List<Rule> rules = db.rules.listForOriginId(originId);
        long start = Long.MIN_VALUE;
        while (true) {
            List<Location> locations = db.locations.paginate(originId, start, 100);
            if (locations.isEmpty()) break;
            for (var location : locations) {
                if (location.lastVisit == null) continue;
                Rule rule = bestMatching(rules, location);
                if (rule == null) continue;
                if (rule.scheduleId == null) continue;
                Schedule schedule = db.schedules.find(rule.scheduleId);
                if (schedule == null) continue;
                Instant nextVisit = schedule.apply(location.lastVisit);
                if (Objects.equals(location.nextVisit, nextVisit)) continue;
                db.locations.updateVisitData(location.originId, location.pathId, location.lastVisit, nextVisit);
            }
            start = locations.get(locations.size() - 1).pathId + 1;
        }
    }
}