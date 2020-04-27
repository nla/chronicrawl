package trickler;

import fi.iki.elonen.NanoHTTPD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;

public class TestServer extends NanoHTTPD implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(TestServer.class);

    @Override
    public Response serve(IHTTPSession session) {
        Response response = handle(session);
        log.trace("{} {} {} {}", session.getMethod(), session.getUri(), response.getStatus(), response.getMimeType());
        return response;
    }

    private Response handle(IHTTPSession session) {
        switch (session.getUri()) {
            case "/robots.txt":
                Response response = newFixedLengthResponse(Response.Status.OK, "text/plain",
                        "Sitemap: /sitemap-index.xml\ncrawl-delay: 5\nDisallow: /no\n");
                response.addHeader("Etag", "\"123\"");
                response.addHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT");
                return response;
            case "/sitemap-index.xml":
                return newFixedLengthResponse(Response.Status.OK, "application/xml",
                        "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
                        "<sitemap><loc>/sitemap.xml</loc></sitemap></sitemapindex>");
            case "/sitemap.xml":
                return newFixedLengthResponse(Response.Status.OK, "application/xml",
                        "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n" +
                        "<url><loc>/no</loc></url>" +
                        "<url><loc>/page</loc><changefreq>daily</changefreq><priority>0.8</priority></url>" +
                                "</urlset>");
            case "/":
                return newFixedLengthResponse(Response.Status.OK, "text/html",
                        "<link rel=stylesheet href=style.css><h1>Hello</h1>");
            case "/style.css":
                return newFixedLengthResponse(Response.Status.OK, "text/css",
                        "body { background: blue; }");
            default:
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not found");
        }
    }

    public TestServer() throws IOException {
        super(InetAddress.getLoopbackAddress().getHostAddress(), 0);
        start();
    }

    public String url() {
        return "http://" + getHostname() + ":" + getListeningPort();
    }

    @Override
    public void close() {
        stop();
    }
}
