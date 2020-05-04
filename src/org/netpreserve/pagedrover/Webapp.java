package org.netpreserve.pagedrover;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import fi.iki.elonen.NanoHTTPD;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

import static fi.iki.elonen.NanoHTTPD.Response.Status.*;
import static java.nio.charset.StandardCharsets.UTF_8;

public class Webapp extends NanoHTTPD implements Closeable {
    private static final PebbleEngine pebble = new PebbleEngine.Builder()
            .strictVariables(true)
            .build();
    private static final SecureRandom random = new SecureRandom();
    private final Crawl crawl;

    static {
        // suppress annoying Broken pipe exception logging
        Logger.getLogger(NanoHTTPD.class.getName()).setFilter(r -> !(r.getThrown() instanceof SocketException)
                || !r.getThrown().getMessage().contains("Broken pipe"));
    }

    private JsonObject oidcConfig;

    public Webapp(Crawl crawl, int port) throws IOException {
        super(port);
        this.crawl = crawl;
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
        try {
            request.parseBody(null);
            return new RequestContext(request).serve();
        } catch (BadRequest e) {
            return newFixedLengthResponse(BAD_REQUEST, "text/plain", e.getMessage());
        } catch (NotFound e) {
            return newFixedLengthResponse(NOT_FOUND, "text/plain", "404 Not found");
        } catch (ResponseException e) {
            return newFixedLengthResponse(e.getStatus(), NanoHTTPD.MIME_PLAINTEXT, e.getMessage());
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return newFixedLengthResponse(INTERNAL_ERROR, "text/plain", e.getMessage() + "\n\n" + sw.toString());
        }
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
                    return View.home.render();
                case "GET /location":
                    Long locationId = paramLong("id");
                    return View.location.render("location", crawl.db.selectLocationById(locationId),
                            "visits", crawl.db.visitsForLocation(locationId));
                case "POST /location/add":
                    String url = param("url");
                    crawl.addSeed(url);
                    return seeOther("/");
                case "GET /log":
                    boolean subresources = request.getParameters().containsKey("subresources");
                    Timestamp after = Optional.ofNullable(param("after", null))
                            .map(Timestamp::valueOf).orElse(Timestamp.from(Instant.now()));
                    return View.log.render("log", crawl.db.paginateCrawlLog(after.toInstant(), !subresources,100),
                            "subresources", subresources, "after", param("after", null));
                case "GET /origin":
                    Long id = paramLong("id", null);
                    if (id == null) {
                        id = new Url(param("url")).originId();
                    }
                    Origin origin = found(crawl.db.selectOriginById(id));
                    return View.origin.render("origin", origin);
                case "GET /visit":
                    UUID visitId = UUID.fromString(param("id"));
                    return View.visit.render("visit", crawl.db.selectVisitById(visitId),
                            "records", crawl.db.selectRecordsByVisitId(visitId));
                default:
                    throw new NotFound();
            }
        }

        private Response authenticate() throws IOException, JsonParserException, SQLException {
            if (crawl.config.oidcUrl == null) {
                session = new Session(null, "anonymous", "admin", null, Instant.MAX);
            }
            crawl.db.expireSesssions();
            String sessionId = request.getCookies().read(crawl.config.uiSessionCookie);
            this.session = sessionId == null ? null : crawl.db.selectSessionById(sessionId).orElse(null);
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
                    crawl.db.updateSessionUsername(sessionId, info.getString("preferred_username"), role);
                }
                return seeOther("/");
            } else if (session == null || session.username == null) {
                // create new session and redirect to auth server
                String id = newSessionId();
                String oidcState = newSessionId();
                crawl.db.insertSession(id, oidcState, Instant.now().plusSeconds(crawl.config.uiSessionExpirySecs));
                String endpoint = oidcConfig().getString("authorization_endpoint");
                String redirectUri = contextUrl() + "/authcb";
                Response response = seeOther(endpoint + "?response_type=code&client_id=" + URLEncoder.encode(crawl.config.oidcClientId, UTF_8) +
                        "&redirect_uri=" + URLEncoder.encode(redirectUri, UTF_8) + "&scope=openid&state=" + URLEncoder.encode(oidcState, UTF_8));
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
            Response response = newFixedLengthResponse(REDIRECT_SEE_OTHER, null, null);
            response.addHeader("Location", location);
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
    }

    static class BadRequest extends RuntimeException {
        public BadRequest(String message) {
            super(message);
        }
    }

    static class NotFound extends RuntimeException {
    }

    private enum View {
        home, location, log, origin, visit;

        private final PebbleTemplate template;

        View() {
            this.template = pebble.getTemplate("org/netpreserve/pagedrover/templates/" + name() + ".peb");
        }

        public Response render(Object... keysAndValues) {
            Map<String, Object> model = new HashMap<>();
            for (int i = 0; i < keysAndValues.length; i += 2) {
                model.put((String)keysAndValues[i], keysAndValues[i + 1]);
            }
            StringWriter buffer = new StringWriter();
            try {
                template.evaluate(buffer, model);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return newFixedLengthResponse(buffer.toString());
        }
    }

    @Override
    public void close() {
        stop();
    }
}
