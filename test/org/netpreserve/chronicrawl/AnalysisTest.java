package org.netpreserve.chronicrawl;

import org.junit.Test;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.WarcResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnalysisTest {
    @Test
    public void test() throws IOException {
        Analysis analysis = new Analysis(null, null);
        analysis.parseHtml(new ByteArrayInputStream(("<title>title1</title><img src=foo.jpg><style>" +
                "@font-face { font-family: somefont; src: url(font.woff);}" +
                "body { background: url(bg.jpg);  }</style><script src=script.js></script><title>title2</title>").getBytes()), null, "http://localhost/");
        var set = new HashSet<String>();
        for (var res : analysis.resources()) {
            set.add(res.url + " " + res.type);
        }
        assertEquals(Set.of("http://localhost/bg.jpg Image",
                "http://localhost/font.woff Font",
                "http://localhost/foo.jpg Image",
                "http://localhost/script.js Script"), set);
        assertEquals("title1", analysis.title);
        assertTrue(analysis.hasScript);
    }

    @Test
    public void testCss() throws IOException {
        Url url = new Url("http://example.org/styles/test.css");
        Analysis analysis = new Analysis(new Location(url), Instant.now());
        WarcResponse response = new WarcResponse.Builder(url.toURI())
                .body(new HttpResponse.Builder(200, "OK")
                        .addHeader("Content-Type", "text/css")
                        .body(Analysis.CSS, "body { background: url(bg.jpg); }".getBytes(UTF_8))
                        .build())
                .build();
        analysis.parsePayload(response, response);
        Analysis.Resource resource = analysis.resources().iterator().next();
        assertEquals("http://example.org/styles/bg.jpg", resource.url.toString());
    }

    @Test
    public void testRedirect() throws IOException {
        Url url = new Url("http://example.org/dir/redirect");
        WarcResponse response = new WarcResponse.Builder(url.toURI())
                .body(new HttpResponse.Builder(302, "Found")
                        .addHeader("Location", "target")
                        .build())
                .build();
        Analysis analysis = new Analysis(new Location(url), Instant.now());
        analysis.parsePayload(response, response);
        Url link = analysis.links().iterator().next();
        assertEquals("http://example.org/dir/target", link.toString());
    }
}