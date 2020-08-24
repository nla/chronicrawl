package org.netpreserve.chronicrawl;

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
            case "/cdx":
                return newFixedLengthResponse(Response.Status.OK, "text/plain",
                        "org,example)/ 20060821020814 http://www.example.org/ text/html 200 EF7YLJGKQUMLJFP3F7A7LBALC65T5W2O - - 525 77993419 crawl-20060821020518.warc.gz\n" +
                                "org,example)/ 20060823203808 http://example.org/ text/html 200 EF7YLJGKQUMLJFP3F7A7LBALC65T5W2O - - 523 111803 crawl-20060823203806.warc.gz\n" +
                                "org,example)/ 20060824035313 http://example.org/ text/html 200 EF7YLJGKQUMLJFP3F7A7LBALC65T5W2O - - 524 58936500 crawl-20060824034615.warc.gz\n" +
                                "org,example)/ 20060917031959 http://example.org/ text/html 200 EF7YLJGKQUMLJFP3F7A7LBALC65T5W2O - - 525 11333459 crawl-20060917031601.warc.gz\n" +
                                "org,example)/ 20110409185614 http://example.org/ - 302 3I42H3S6NNFQ2MSVX7XZKYAYSCX5QBYJ http://www.iana.org/domains/example/ - 337 292552577 crawl-20110409185451.warc.gz"
                );
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
