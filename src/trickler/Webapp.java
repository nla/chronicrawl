package trickler;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import fi.iki.elonen.NanoHTTPD;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import static fi.iki.elonen.NanoHTTPD.Response.Status.*;

public class Webapp extends NanoHTTPD implements Closeable {
    private static PebbleEngine pebble = new PebbleEngine.Builder()
            .strictVariables(true)
            .build();

    private final Crawl crawl;

    public Webapp(Crawl crawl, int port) throws IOException {
        super(port);
        this.crawl = crawl;
        start();
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            session.parseBody(null);
            return new RequestContext(session).serve();
        } catch (IOException e) {
            return newFixedLengthResponse(INTERNAL_ERROR, "text/plain", e.getMessage());
        } catch (BadRequest e) {
            return newFixedLengthResponse(BAD_REQUEST, "text/plain", e.getMessage());
        } catch (NotFound e) {
            return newFixedLengthResponse(NOT_FOUND, "text/plain", "404 Not found");
        } catch (ResponseException e) {
            return newFixedLengthResponse(e.getStatus(), NanoHTTPD.MIME_PLAINTEXT, e.getMessage());
        }
    }

    private class RequestContext {
        final IHTTPSession session;

        private RequestContext(IHTTPSession session) {
            this.session = session;
        }

        private Response serve() {
            switch (session.getMethod().name() + " " + session.getUri()) {
                case "GET /":
                    return View.home.render();
                case "POST /location/add":
                    String url = param("url");
                    crawl.addSeed(url);
                    return seeOther("/");
                case "GET /origin":
                    Long id = paramLong("id", null);
                    if (id == null) {
                        id = new Url(param("url")).originId();
                    }
                    Origin origin = found(crawl.db.selectOriginById(id));
                    return View.origin.render("origin", origin);
                default:
                    throw new NotFound();
            }
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
            var values = session.getParameters().get(name);
            if (values == null || values.isEmpty()) return defaultValue;
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
        home, origin;

        private final PebbleTemplate template;

        View() {
            this.template = pebble.getTemplate("trickler/templates/" + name() + ".peb");
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
