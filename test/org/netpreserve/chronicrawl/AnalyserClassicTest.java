package org.netpreserve.chronicrawl;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class AnalyserClassicTest {
    @Test
    public void test() throws IOException {
        Analysis analysis = new Analysis(null, null);
        new AnalyserClassic(analysis).parseHtml(new ByteArrayInputStream(("<title>title1</title><img src=foo.jpg><style>" +
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
}