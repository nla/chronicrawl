package org.netpreserve.chronicrawl;

import com.grack.nanojson.*;
import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import fi.iki.elonen.NanoHTTPD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fi.iki.elonen.NanoHTTPD.Response.Status.*;
import static java.lang.Integer.parseInt;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.unbescape.html.HtmlEscape.escapeHtml5;

public class Webapp extends NanoHTTPD implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(Webapp.class);
    private static final PebbleEngine pebble = new PebbleEngine.Builder()
            .strictVariables(true)
            .build();
    private static final SecureRandom random = new SecureRandom();

    static { // suppress annoying Broken pipe exception logging
        java.util.logging.Logger.getLogger(NanoHTTPD.class.getName()).setFilter(r -> !(r.getThrown() instanceof SocketException)
                || !r.getThrown().getMessage().contains("Broken pipe"));
    }

    private final Crawl crawl;
    private final Database db;
    private final String contextPath;
    private JsonObject oidcConfig;

    public Webapp(Crawl crawl, int port) throws IOException {
        super(port);
        this.crawl = crawl;
        this.db = crawl.db;
        this.contextPath = crawl.config.uiContextPath.replaceFirst("/+$", "");
        start();
        log.info("Started Chronicrawl web interface on " + port);
    }

    public synchronized JsonObject oidcConfig() {
        if (oidcConfig == null) {
            String url = crawl.config.oidcUrl.replaceFirst("/+$", "") + "/.well-known/openid-configuration";
            try {
                this.oidcConfig = JsonParser.object().from(new URL(url));
            } catch (JsonParserException | MalformedURLException e) {
                throw new RuntimeException("Error fetching " + url, e);
            }
        }
        return oidcConfig;
    }

    @Override
    public Response serve(IHTTPSession request) {
        Response response;
        try {
            request.parseBody(null);
            response = new RequestContext(request).serve();
        } catch (BadRequest e) {
            response = newFixedLengthResponse(BAD_REQUEST, "text/plain", e.getMessage());
        } catch (NotFound e) {
            response = newFixedLengthResponse(NOT_FOUND, "text/plain", "404 Not found");
        } catch (MissingRoleException e) {
            response = newFixedLengthResponse(FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, e.getMessage());
        } catch (ResponseException e) {
            response = newFixedLengthResponse(e.getStatus(), NanoHTTPD.MIME_PLAINTEXT, e.getMessage());
        } catch (Exception e) {
            log.error(request.getUri(), e);
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stackTrace = html(sw.toString())
                    .replaceAll("(at .*)", "<span class=at>$1</span>")
                    .replaceAll("(at org\\.netpreserve\\..*)", "<span class=our>$1</span>");

            response = newFixedLengthResponse(INTERNAL_ERROR, "text/html",
                    "<!doctype html><style>pre { white-space: pre-wrap; }" +
                            ".err { color: #000; }" +
                            ".at { color: #444; }" +
                            ".our { color: #050; }</style>\n<pre>" +
                            "<span class=err>" + html(e.getMessage()) + "</span>\n\n" + stackTrace + "</pre>");
        }
        log.info(request.getMethod() + " " + request.getUri() + (request.getQueryParameterString() == null ? "" : "?" + request.getQueryParameterString()) + " " + response.getStatus());
        return response;
    }
    private static String html(String s) {
        return s == null ? null : s.replace("&", "&amp;").replace("<", "&lt;");
    }

    public static class Session {
        final String id;
        final String username;
        final String role;
        final String oidcState;
        final Instant expiry;

        public Session(String id, String username, String role, String oidcState, Instant expiry) {
            this.id = id;
            this.username = username;
            this.role = role;
            this.oidcState = oidcState;
            this.expiry = expiry;
        }
    }

    private class RequestContext {
        final IHTTPSession request;
        private String role;
        private Session session;

        private RequestContext(IHTTPSession request) {
            this.request = request;
        }

        private Response serve() throws Exception {
            if (!request.getUri().startsWith(contextPath + "/")) return seeOther(contextPath + "/");
            String relpath = request.getUri().substring(contextPath.length());

            String pywbAuthPrefix = "/auth/" + crawl.pywb.authKey + "/";
            if (relpath.startsWith(pywbAuthPrefix)) {
                relpath = relpath.substring(pywbAuthPrefix.length() - 1);
                role = "pywb";
            } else {
                Response authResponse = authenticate();
                if (authResponse != null) return authResponse;
                role = "admin";
            }
            switch (request.getMethod().name() + " " + relpath) {
                case "GET /":
                    requireRole("admin");
                    return render(View.home, "paused", crawl.paused.get(),
                            "screenshots", crawl.db.screenshotCache.getN(0, 12));
                case "GET /analyse": {
                    requireRole("admin");
                    long originId = paramLong("o");
                    long pathId = paramLong("p");
                    Instant date = Instant.ofEpochMilli(paramLong("d"));
                    var location = db.locations.find(originId, pathId);
                    var analysis = new Analysis(crawl, location, date, request.getParameters().containsKey("recordMode"));
                    for (var resource : analysis.resources()) {
                        if (resource.visit == null) {
                            resource.visit = db.visits.findClosest(resource.url.originId(), resource.url.pathId(), analysis.visitDate, resource.method);
                        }
                    }
                    return render(View.analyse, "analysis", analysis);
                }
                case "GET /cdx": {
                    requireRole("admin", "pywb");
                    Url url = new Url(param("url"));
                    var lines = new ArrayList<>(db.visits.asCdxLines(url.originId(), url.pathId()));
                    if (url.scheme().equals("http")) {
                        Url altUrl = url.withScheme("https");
                        lines.addAll(db.visits.asCdxLines(altUrl.originId(), altUrl.pathId()));
                    }
                    StringBuilder sb = new StringBuilder();
                    for (var line : lines) {
                        sb.append(line.toString());
                        sb.append('\n');
                    }
                    return newFixedLengthResponse(sb.toString());
                }
                case "GET /debug": {
                    requireRole("admin");
                    return render(View.debug);
                }
                case "GET /diff": {
                    requireRole("admin");
                    Visit visit1 = db.visits.find(paramLong("o"), paramLong("p"), Instant.ofEpochMilli(paramLong("d1")));
                    Visit visit2 = db.visits.find(paramLong("o"), paramLong("p"), Instant.ofEpochMilli(paramLong("d2")));
                    String text1 = crawl.storage.text(visit1);
                    String text2 = crawl.storage.text(visit2);
                    var dmp = new DiffMatchPatch();
                    var diff = dmp.diff_main(text1, text2);
                    dmp.diff_cleanupSemantic(diff);
                    StringBuilder sb = new StringBuilder();
                    sb.append("<!doctype html><style>strike { color: red; } u { color: green; }</style>");
                    sb.append("<div style='white-space: pre-wrap'>");
                    for (var fragment : diff) {
                        switch (fragment.operation) {
                            case EQUAL:
                                sb.append(escapeHtml5(fragment.text));
                                break;
                            case DELETE:
                                sb.append("<strike>").append(escapeHtml5(fragment.text)).append("</strike>");
                                break;
                            case INSERT:
                                sb.append("<u>").append(escapeHtml5(fragment.text)).append("</u>");
                                break;
                        }
                    }
                    sb.append("</div>");
                    return newFixedLengthResponse(OK, "text/html", sb.toString());

                }
                case "POST /debug/load-dummy-data": {
                    requireRole("admin");
                    int origins = 100;
                    int locations = 1000;
                    long start = System.currentTimeMillis();
                    db.jdbi.inTransaction(h -> {
                        for (int i = 0; i < origins; i++) {
                            String origin = "http://" + UUID.randomUUID().toString() + ".localhost";
                            db.origins.tryInsert(Url.hash(origin), origin, Instant.now(), CrawlPolicy.CONTINUOUS);
                            for (int j = 0; j < locations; j++) {
                                db.locations.tryInsert(new Url(origin + "/" + UUID.randomUUID()), Location.Type.PAGE, null, 0, Instant.now());
                            }
                        }
                        return null;
                    });
                    return seeOther(contextPath + "/debug", "Loaded " + origins + " random origins each with " + locations + " locations in " + (System.currentTimeMillis() - start) + " ms");
                }
                case "GET /location": {
                    requireRole("admin");
                    Location location = db.locations.find(paramLong("o"), paramLong("p"));

                    return render(View.location, "location", location,
                            "via", location.viaOriginId == null || location.viaPathId == null ? null :
                                    db.locations.find(location.viaOriginId, location.viaPathId),
                            "visits", crawl.listVisits(location));
                }
                case "POST /location/add": {
                    requireRole("admin");
                    String url = param("url");
                    crawl.addSeed(url);
                    return seeOther(contextPath + "/", "Added.");
                }
                case "POST /location/visit-now": {
                    requireRole("admin");
                    Location location = db.locations.find(paramLong("o"), paramLong("p"));
                    Origin origin = db.origins.find(location.originId);
                    try (Exchange exchange = new Exchange(crawl, origin, location, "GET", Map.of())) {
                        exchange.run();
                        if (exchange.fetchStatus < 0) {
                            return seeOther(contextPath + "/" + location.href(), "Visit failed! (" + exchange.fetchStatus + ")");
                        } else {
                            return seeOther(contextPath + "/visit?o=" + location.originId + "&p=" + location.pathId
                                    + "&d=" + exchange.date.toEpochMilli(), "Visit successful!");
                        }
                    }
                }
                case "GET /log":
                    requireRole("admin");
                    boolean subresources = request.getParameters().containsKey("subresources");
                    Timestamp after = Optional.ofNullable(param("after", null))
                            .map(s -> Timestamp.from(Instant.ofEpochMilli(Long.parseLong(s))))
                            .orElse(Timestamp.from(Instant.now()));
                    List<Map<String, Object>> log = subresources ? db.visits.paginateCrawlLog(after.toInstant(), 100) :
                            db.visits.paginateCrawlLogPagesOnly(after.toInstant(), 100);
                    return render(View.log, "log", log, "subresources", subresources, "after", param("after", null));
                case "GET /origin": {
                    requireRole("admin");
                    Long id = paramLong("id", null);
                    if (id == null) {
                        id = new Url(param("url")).originId();
                    }
                    Origin origin = found(db.origins.find(id));
                    return render(View.origin, "origin", origin,
                            "rules", db.rules.listForOriginId(id),
                            "queue", db.locations.peek(id, 50),
                            "allCrawlPolicies", CrawlPolicy.values());
                }
                case "POST /origin/update": {
                    requireRole("admin");
                    Long id = paramLong("id", null);
                    Origin old = db.origins.find(id);
                    CrawlPolicy crawlPolicy = CrawlPolicy.valueOf(param("crawlPolicy"));
                    db.origins.updateCrawlPolicy(id, crawlPolicy);
                    return seeOther(contextPath + "/origin?id=" + id, "Changed crawl policy from " + old.crawlPolicy + " to " + crawlPolicy + ".");
                }
                case "GET /queue": {
                    requireRole("admin");
                    var queues = new ArrayList<QueueInfo>();
                    for (var origin : db.origins.peek(25)) {
                        queues.add(new QueueInfo(origin, db.locations.peek(origin.id, 10)));
                    }
                    return render(View.queue, "queues", queues);
                }
                case "POST /pause":
                    requireRole("admin");
                    crawl.paused.set(true);
                    return seeOther(contextPath + "/", "Paused.");
                case "POST /unpause":
                    requireRole("admin");
                    crawl.paused.set(false);
                    return seeOther(contextPath + "/", "Unpaused.");
                case "GET /recent.json": {
                    requireRole("admin");
                    var json = JsonWriter.string().array();
                    for (var screenshot : db.screenshotCache.getN(paramLong("after", 0L), 5)) {
                        json.object()
                                .value("originId", Long.toString(screenshot.originId))
                                .value("pathId", Long.toString(screenshot.pathId))
                                .value("date", Long.toString(screenshot.date.toEpochMilli()))
                                .value("screenshotDataUrl", screenshot.screenshotDataUrl())
                                .value("url", screenshot.url.toString())
                                .value("host", screenshot.url.host().replaceFirst("^www\\.", ""))
                                .value("path", screenshot.url.path())
                                .end();
                    }
                    return newFixedLengthResponse(OK, "application/json", json.end().done());
                }
                case "GET /record/serve": {
                    requireRole("admin", "pywb");
                    UUID id = UUID.fromString(param("id"));
                    String path = db.warcs.findPath(id);
                    if (path == null) throw new NotFound();
                    String range = request.getHeaders().get("range");
                    Matcher matcher = Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(range);
                    if (!matcher.matches()) throw new BadRequest("invalid range");
                    long rangeStart = Long.parseLong(matcher.group(1));
                    long rangeEnd = Long.parseLong(matcher.group(2));
                    long length = rangeEnd - rangeStart - 1;
                    if (length < 0) throw new BadRequest("negative range length");
                    FileChannel channel = FileChannel.open(Paths.get(path));
                    channel.position(rangeStart);
                    return newFixedLengthResponse(OK, "application/warc", Channels.newInputStream(channel), length);
                }
                case "GET /rule": {
                    requireRole("admin");
                    long originId = paramLong("o");
                    String pattern = param("p", null);
                    Rule rule = pattern == null ? null : db.rules.find(originId, pattern);
                    return render(View.rule, "schedules", db.schedules.list(),
                            "rule", rule, "originId", originId, "pattern", pattern);
                }
                case "POST /rule": {
                    requireRole("admin");
                    long originId = paramLong("o");
                    String p = param("p", null);
                    String action;
                    if (request.getParameters().containsKey("delete")) {
                        db.rules.delete(originId, p);
                        action = "deleted";
                    } else if (p == null || db.rules.find(originId, p) == null) {
                        db.rules.insert(originId, param("pattern"), paramLong("scheduleId", null));
                        action = "created";
                    } else {
                        db.rules.update(originId, p, param("pattern"), paramLong("scheduleId", null));
                        action = "updated";
                    }
                    Rule.reapplyRulesToOrigin(db, originId);
                    return seeOther(contextPath + "/origin?id=" + originId, "Rule " + param("pattern") + " " + action + ".");
                }
                case "GET /search": {
                    requireRole("admin");
                    String q = param("q").strip();
                    Url url = new Url(q);
                    Location location = db.locations.find(url.originId(), url.pathId());
                    if (location != null) return seeOther(location.href());
                    if (db.origins.find(url.originId()) != null) return seeOther("origin?id=" + url.originId());
                    return render(View.searchNoResults, "url", url);
                }
                case "GET /settings": {
                    requireRole("admin");
                    return render(View.settings);
                }
                case "GET /settings/config": {
                    requireRole("admin");
                    return render(View.config, "config", crawl.config, "dbConfig", db.config.getAll());
                }
                case "POST /settings/config": {
                    requireRole("admin");
                    Config testConfig = new Config();
                    for (String name : request.getParameters().getOrDefault("set", List.of())) {
                        testConfig.set(name, param(name));
                    }
                    db.jdbi.inTransaction(h -> {
                        db.config.deleteAll();
                        for (String name : request.getParameters().getOrDefault("set", List.of())) {
                            db.config.insert(name, param(name));
                        }
                        return null;
                    });
                    crawl.config.load(db.config.getAll());
                    return seeOther(contextPath + "/settings", "Config updated.");
                }
                case "GET /settings/schedules": {
                    requireRole("admin");
                    return render(View.schedules, "schedules", db.schedules.list());
                }
                case "GET /settings/schedule": {
                    requireRole("admin");
                    Long id = paramLong("id", null);
                    Schedule schedule = id == null ? null : db.schedules.find(id);
                    return render(View.schedule, "schedule", schedule,
                            "dayNames", Schedule.dayNames(),
                            "hourNames", Schedule.hourNames());
                }
                case "POST /settings/schedule": {
                    requireRole("admin");
                    Long id = paramLong("id", null);
                    if (id == null) {
                        db.schedules.insert(db.ids.next(), param("name"), parseInt(param("years")),
                                parseInt(param("months")), parseInt(param("days")),
                                toBits(request.getParameters().get("dayOfWeek")),
                                toBits(request.getParameters().get("hourOfDay")));
                    } else {
                        db.schedules.update(id, param("name"), parseInt(param("years")),
                                parseInt(param("months")), parseInt(param("days")),
                                toBits(request.getParameters().get("dayOfWeek")),
                                toBits(request.getParameters().get("hourOfDay")));
                    }
                    return seeOther(contextPath + "/settings/schedules", "Schedule saved.");
                }
                case "POST /settings/schedule/delete": {
                    requireRole("admin");
                    db.schedules.delete(paramLong("id"));
                    return seeOther(contextPath + "/settings/schedules", "Schedule deleted.");
                }
                case "GET /metrics.svg": {
                    requireRole("admin");

                    Instant start = Instant.now().minusSeconds(15 * 60);
                    Instant end = Instant.now();

                    long startMillis = start.toEpochMilli();
                    long endMillis = end.toEpochMilli();
                    long intervalMillis = (endMillis - startMillis) / 300;
                    int ticks = (int)((endMillis - startMillis) / intervalMillis);

                    long[] values = new long[ticks];

                    List<Database.Metric> metrics = db.visits.metrics(start, end, intervalMillis);
                    for (var metric : metrics) {
                        values[(int)((metric.millis - startMillis) / intervalMillis)] = metric.bytes;
                    }
                    var stats = Arrays.stream(values).summaryStatistics();

                    int chartHeight = 100;
                    int chartWidth = 800;
                    long max = stats.getMax();
                    if (max == 0) max = 1;

                    StringWriter sw = new StringWriter();
                    XMLStreamWriter xml = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(sw);
                    xml.writeStartDocument("UTF-8", "1.0");
                    xml.writeStartElement("svg");
                    xml.writeAttribute("xmlns", "http://www.w3.org/2000/svg");
                    int x = 0;
                    int barWidth = chartWidth / ticks;
                    for (int i = 0; i < values.length; i++) {
                        int barHeight = (int)(values[i] * chartHeight / max);
                        int y = chartHeight - barHeight;
                        if (barHeight > 0) {
                            xml.writeEmptyElement("rect");
                            xml.writeAttribute("fill", "#66f");
                            xml.writeAttribute("x", Integer.toString(x));
                            xml.writeAttribute("y", Integer.toString(y));
                            xml.writeAttribute("width", Integer.toString(barWidth));
                            xml.writeAttribute("height", Integer.toString(barHeight));
                            xml.writeCharacters("\n");
                        }
                        x += barWidth;
                    }

                    int textHeight = 16;
                    xml.writeStartElement("text");
                    xml.writeAttribute("x", Integer.toString(chartWidth));
                    xml.writeAttribute("y", Integer.toString(textHeight));
                    xml.writeCharacters(max / 1024 + " KB");
                    xml.writeEndElement();

                    xml.writeStartElement("text");
                    xml.writeAttribute("x", Integer.toString(chartWidth));
                    xml.writeAttribute("y", Integer.toString(chartHeight));
                    xml.writeCharacters("0 KB");
                    xml.writeEndElement();

                    xml.writeEndElement();
                    xml.writeEndDocument();
                    return newFixedLengthResponse(OK, "image/svg+xml", sw.toString());
                }
                case "GET /visit": {
                    requireRole("admin");
                    Visit visit = db.visits.find(paramLong("o"), paramLong("p"), Instant.ofEpochMilli(paramLong("d")));
                    if (visit == null) throw new NotFound();
                    Location location = db.locations.find(visit.originId, visit.pathId);
                    return render(View.visit, "visit", visit,
                            "location", location,
                            "replayUrl", crawl.pywb.replayUrl(location.url, visit.date),
                            "requestHeader", visit.warcId == null ? null : crawl.storage.slurpHeaders(visit.warcId, visit.requestPosition),
                            "responseHeader", visit.warcId == null ? null : crawl.storage.slurpHeaders(visit.warcId, visit.responsePosition));
                }
                case "GET /visit/text": {
                    requireRole("admin");
                    Visit visit = db.visits.find(paramLong("o"), paramLong("p"), Instant.ofEpochMilli(paramLong("d")));
                    if (visit == null) throw new NotFound();
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", crawl.storage.text(visit));
                }
                default:
                    throw new NotFound();
            }
        }

        private int toBits(List<String> values) {
            if (values == null) return 0;
            int bits = 0;
            for (String value : values) {
                bits |= 1 << parseInt(value);
            }
            return bits;
        }

        private void requireRole(String... requiredRoles) {
            for (String requiredRole : requiredRoles) {
                if (requiredRole.equals(this.role)) {
                    return;
                }
            }
            throw new MissingRoleException("Access denied. One of the following roles must be assigned: " + List.of(requiredRoles).toString());
        }

        private Response authenticate() throws IOException, JsonParserException {
            if (crawl.config.oidcUrl == null) {
                session = new Session(null, "anonymous", "admin", null, Instant.MAX);
                return null;
            }
            db.sessions.expire();
            String sessionId = request.getCookies().read(crawl.config.uiSessionCookie);
            session = sessionId == null ? null : db.sessions.find(sessionId).orElse(null);
            if (session != null && request.getUri().equals(contextPath + "/authcb") && param("state").equals(session.oidcState)) {
                // exchange code for access token
                var conn = (HttpURLConnection) new URL(oidcConfig().getString("token_endpoint")).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (crawl.config.oidcClientId + ":" + crawl.config.oidcClientSecret).getBytes(UTF_8)));
                try (var out = conn.getOutputStream()) {
                    out.write(("grant_type=authorization_code&code=" + URLEncoder.encode(param("code"), UTF_8) +
                            "&redirect_uri=" + URLEncoder.encode(contextUrl() + "/authcb", UTF_8)).getBytes(UTF_8));
                }
                String accessToken;
                try (var stream = conn.getInputStream()) {
                    accessToken = JsonParser.object().from(stream).getString("access_token");
                }

                // introspect token
                conn = (HttpURLConnection) new URL(oidcConfig().getString("introspection_endpoint")).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((crawl.config.oidcClientId + ":" + crawl.config.oidcClientSecret).getBytes(UTF_8)));
                try (var out = conn.getOutputStream()) {
                    out.write(("token=" + accessToken).getBytes(UTF_8));
                }
                try (var stream = conn.getInputStream()) {
                    var info = JsonParser.object().from(stream);
                    JsonObject ra = info.getObject("resource_access");
                    JsonObject cli = ra == null ? null : ra.getObject(crawl.config.oidcClientId);
                    JsonArray roles = cli == null ? null : cli.getArray("roles");
                    String role = roles != null && roles.contains("admin") ? "admin" : "anonymous";
                    db.sessions.update(sessionId, info.getString("preferred_username"), role);
                }
                return seeOther(contextPath + "/", "Logged in.");
            } else if (session == null || session.username == null) {
                // create new session and redirect to auth server
                String id = newSessionId();
                String oidcState = newSessionId();
                db.sessions.insert(id, oidcState, Instant.now().plusSeconds(crawl.config.uiSessionExpirySecs));
                String endpoint = oidcConfig().getString("authorization_endpoint");
                String redirectUri = contextUrl() + "/authcb";
                String authUrl = endpoint + "?response_type=code&client_id=" + URLEncoder.encode(crawl.config.oidcClientId, UTF_8) +
                        "&redirect_uri=" + URLEncoder.encode(redirectUri, UTF_8) + "&scope=openid&state=" + URLEncoder.encode(oidcState, UTF_8);
                Response response = seeOther(authUrl);
                response.addHeader("Set-Cookie", crawl.config.uiSessionCookie + "=" + id + "; Max-Age=" + crawl.config.uiSessionExpirySecs + "; HttpOnly; SameSite=Lax" + (isSecure() ? "; Secure" : ""));
                return response;
            }
            return null;
        }

        private String contextUrl() {
            return request.getHeaders().getOrDefault("x-forwarded-proto", "http") + "://" + request.getHeaders().get("host") + contextPath;
        }

        private boolean isSecure() {
            return request.getHeaders().getOrDefault("x-forwarded-proto", "http").equals("https");
        }

        private String newSessionId() {
            byte[] bytes = new byte[20];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        private Response seeOther(String location) {
            return seeOther(location, null);
        }

        private Response seeOther(String location, String flash) {
            Response response = newFixedLengthResponse(REDIRECT_SEE_OTHER, null, null);
            response.addHeader("Location", location);
            if (flash != null) {
                response.addHeader("Set-Cookie", "flash=" + Base64.getUrlEncoder().encodeToString(flash.getBytes(UTF_8)) + "; HttpOnly; Max-Age=60; SameSite=Lax" + (isSecure() ? "; Secure" : ""));
            }
            return response;
        }

        <P> P found(P value) {
            if (value == null) throw new NotFound();
            return value;
        }
        <P> P mandatory(String name, P value) {
            if (value == null) throw new BadRequest("Missing mandatory parameter " + name);
            return value;
        }

        Long paramLong(String name) {
            return mandatory(name, paramLong(name, null));
        }

        private Long paramLong(String name, Long defaultValue) {
            String value = param(name, null);
            return value == null ? defaultValue : Long.valueOf(value);
        }

        private String param(String name) {
            return mandatory(name, param(name, null));
        }

        private String param(String name, String defaultValue) {
            var values = request.getParameters().get(name);
            if (values == null || values.isEmpty() || values.get(0).isEmpty()) return defaultValue;
            return values.get(0);
        }

        public Response render(View view, Object... keysAndValues) {
            Map<String, Object> model = new HashMap<>();
            model.put("contextPath", contextPath);
            String flash = request.getCookies().read("flash");
            if (flash != null) {
                request.getCookies().delete("flash");
                model.put("flash", new String(Base64.getUrlDecoder().decode(flash), UTF_8));
            } else {
                model.put("flash", null);
            }
            for (int i = 0; i < keysAndValues.length; i += 2) {
                model.put((String)keysAndValues[i], keysAndValues[i + 1]);
            }
            StringWriter buffer = new StringWriter();
            try {
                view.template.evaluate(buffer, model);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return newFixedLengthResponse(buffer.toString());
        }
    }

    static class BadRequest extends RuntimeException {
        public BadRequest(String message) {
            super(message);
        }
    }

    static class NotFound extends RuntimeException {
    }

    static class MissingRoleException extends RuntimeException {
        public MissingRoleException(String message) {
            super(message);
        }
    }

    private enum View {
        home, location, log, origin, visit, analyse, searchNoResults, queue, debug, schedule, schedules, settings, rule, config;

        private final PebbleTemplate template;

        View() {
            this.template = pebble.getTemplate("org/netpreserve/chronicrawl/templates/" + name() + ".peb");
        }
    }

    @Override
    public void close() {
        stop();
    }

    public static class QueueInfo {
        public final Origin origin;
        public final List<Location> locations;

        public QueueInfo(Origin origin, List<Location> locations) {
            this.origin = origin;
            this.locations = locations;
        }
    }
}
