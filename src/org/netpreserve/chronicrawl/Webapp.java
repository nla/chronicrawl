package org.netpreserve.chronicrawl;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import fi.iki.elonen.NanoHTTPD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fi.iki.elonen.NanoHTTPD.Response.Status.*;
import static java.nio.charset.StandardCharsets.UTF_8;

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
    private JsonObject oidcConfig;

    public Webapp(Crawl crawl, int port) throws IOException {
        super(port);
        this.crawl = crawl;
        this.db = crawl.db;
        start();
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
        } catch (ResponseException e) {
            response = newFixedLengthResponse(e.getStatus(), NanoHTTPD.MIME_PLAINTEXT, e.getMessage());
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            log.error(request.getUri(), e);
            response = newFixedLengthResponse(INTERNAL_ERROR, "text/plain", e.getMessage() + "\n\n" + sw.toString());
        }
        log.info(request.getMethod() + " " + request.getUri() + (request.getQueryParameterString() == null ? "" : "?" + request.getQueryParameterString()) + " " + response.getStatus());
        return response;
    }

    static class Session {
        final String id;
        final String username;
        final String role;
        final String oidcState;
        final Instant expiry;

        Session(String id, String username, String role, String oidcState, Instant expiry) {
            this.id = id;
            this.username = username;
            this.role = role;
            this.oidcState = oidcState;
            this.expiry = expiry;
        }
    }

    private class RequestContext {
        final IHTTPSession request;
        private Session session;

        private RequestContext(IHTTPSession request) {
            this.request = request;
        }

        private Response serve() throws Exception {
            Response authResponse = authenticate();
            if (authResponse != null) return authResponse;
            if (!session.role.equals("admin")) {
                return newFixedLengthResponse(FORBIDDEN, "text/plain", "Access denied. Missing role 'admin'.");
            }
            switch (request.getMethod().name() + " " + request.getUri()) {
                case "GET /":
                    return render(View.home, "paused", crawl.paused.get());
                case "GET /analyse": {
                    UUID visitId = UUID.fromString(param("visitId"));
                    var visit = db.visits.find(visitId);
                    var location = db.locations.find(visit.locationId);
                    var analysis = new Analysis(location.url(), visit.date);
                    crawl.storage.readResponseForVisit(visitId, response -> new AnalyserClassic(analysis)
                            .parseHtml(response));
                    if (analysis.hasScript) {
                        new AnalyserBrowser(crawl, analysis, request.getParameters().containsKey("recordMode"));
                    }
                    return render(View.analyse, "analysis", analysis);
                }
                case "GET /cdx": {
                    Url url = new Url(param("url"));
                    var lines = new ArrayList<Database.CdxLine>();
                    lines.addAll(db.records.listResponsesByLocationId(url.id()));
                    if (url.scheme().equals("http")) {
                        lines.addAll(db.records.listResponsesByLocationId(url.withScheme("https").id()));
                    }
                    StringBuilder sb = new StringBuilder();
                    for (var line : lines) {
                        sb.append(line.toString());
                        sb.append('\n');
                    }
                    return newFixedLengthResponse(sb.toString());
                }
                case "GET /location":
                    Long locationId = paramLong("id");
                    return render(View.location, "location", db.locations.find(locationId), "visits", db.visits.findByLocationId(locationId));
                case "POST /location/add": {
                    String url = param("url");
                    crawl.addSeed(url);
                    return seeOther("/", "Added.");
                }
                case "GET /log":
                    boolean subresources = request.getParameters().containsKey("subresources");
                    Timestamp after = Optional.ofNullable(param("after", null))
                            .map(Timestamp::valueOf).orElse(Timestamp.from(Instant.now()));
                    Object[] keysAndValues = new Object[]{"log", db.paginateCrawlLog(after.toInstant(), !subresources,100), "subresources", subresources, "after", param("after", null)};
                    return render(View.log, keysAndValues);
                case "GET /origin": {
                    Long id = paramLong("id", null);
                    if (id == null) {
                        id = new Url(param("url")).originId();
                    }
                    Origin origin = found(db.origins.find(id));
                    return render(View.origin, "origin", origin, "queue", db.locations.peekQueue(id));
                }
                case "GET /queue": {
                    return render(View.queue, "locations", db.locations.peekQueue());
                }
                case "POST /pause":
                    crawl.paused.set(true);
                    return seeOther("/", "Paused.");
                case "POST /unpause":
                    crawl.paused.set(false);
                    return seeOther("/", "Unpaused.");
                case "GET /record/serve": {
                    UUID id = UUID.fromString(param("id"));
                    Path path = Paths.get(db.warcs.findPath(id));
                    if (path == null) throw new NotFound();
                    String range = request.getHeaders().get("range");
                    Matcher matcher = Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(range);
                    if (!matcher.matches()) throw new BadRequest("invalid range");
                    long rangeStart = Long.parseLong(matcher.group(1));
                    long rangeEnd = Long.parseLong(matcher.group(2));
                    long length = rangeEnd - rangeStart - 1;
                    if (length < 0) throw new BadRequest("negative range length");
                    FileChannel channel = FileChannel.open(path);
                    channel.position(rangeStart);
                    return newFixedLengthResponse(OK, "application/warc", Channels.newInputStream(channel), length);
                }
                case "GET /search": {
                    String q = param("q").strip();
                    if (q.matches("[0-9-]+") && db.locations.find(Long.parseLong(q)) != null)
                        return seeOther("location?id=" + q);
                    Url url = new Url(q);
                    if (db.locations.find(url.id()) != null) return seeOther("location?id=" + url.id());
                    if (db.origins.find(url.originId()) != null) return seeOther("origin?id=" + url.originId());
                    return render(View.searchNoResults, "url", url);
                }
                case "GET /visit":
                    UUID visitId = UUID.fromString(param("id"));
                    Visit visit = db.visits.find(visitId);
                    if (visit == null) throw new NotFound();
                    Location location = db.locations.find(visit.locationId);
                    return render(View.visit, "visit", visit,
                            "location", location,
                            "records", db.records.findByVisitId(visitId),
                            "replayUrl", crawl.pywb.replayUrl(location.url(), visit.date));
                default:
                    throw new NotFound();
            }
        }

        private Response authenticate() throws IOException, JsonParserException, SQLException {
            if (crawl.config.oidcUrl == null) {
                session = new Session(null, "anonymous", "admin", null, Instant.MAX);
                return null;
            }
            db.sessions.expire();
            String sessionId = request.getCookies().read(crawl.config.uiSessionCookie);
            this.session = sessionId == null ? null : db.sessions.find(sessionId).orElse(null);
            if (session != null && request.getUri().equals("/authcb") && param("state").equals(session.oidcState)) {
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
                return seeOther("/", "Logged in.");
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
                response.addHeader("Set-Cookie", crawl.config.uiSessionCookie + "=" + id + "; Max-Age=" + crawl.config.uiSessionExpirySecs + "; HttpOnly");
                return response;
            }
            return null;
        }

        private String contextUrl() {
            return request.getHeaders().getOrDefault("X-Forwarded-Proto", "http") + "://" + request.getHeaders().get("host");
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
                response.addHeader("Set-Cookie", "flash=" + URLEncoder.encode(flash, UTF_8) + "; HttpOnly; Max-Age=60");
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
            return value == null ? defaultValue : Long.parseLong(value);
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
            String flash = request.getCookies().read("flash");
            if (flash != null) {
                request.getCookies().delete("flash");
                model.put("flash", URLDecoder.decode(flash, UTF_8));
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

    private enum View {
        home, location, log, origin, visit, analyse, searchNoResults, queue;

        private final PebbleTemplate template;

        View() {
            this.template = pebble.getTemplate("org/netpreserve/chronicrawl/templates/" + name() + ".peb");
        }
    }

    @Override
    public void close() {
        stop();
    }
}
