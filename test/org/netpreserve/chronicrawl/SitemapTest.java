package org.netpreserve.chronicrawl;

import org.junit.Test;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class SitemapTest {

    @Test
    public void parse() throws IOException, XMLStreamException {
        List<Sitemap.Entry> list = new ArrayList<>();
        try (InputStream stream = getClass().getResourceAsStream("example-sitemap.xml")) {
            Sitemap.parse(stream, list::add);
        }

        System.out.println(list);

    }
}