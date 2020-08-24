package org.netpreserve.chronicrawl;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.BaseNCodec;
import org.apache.hc.core5.net.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ExternalArchive {
    private static final Logger log = LoggerFactory.getLogger(ExternalArchive.class);
    private final BaseNCodec digestCodec = new Base32();
    private final String cdxUrl;
    private final int cdxLimit = 100;

    public ExternalArchive(String cdxUrl) {
        this.cdxUrl = cdxUrl;
    }

    public List<Visit> list(String url) {
        try {
            List<Visit> visits = new ArrayList<>();
            URI uri = new URIBuilder(cdxUrl)
                    .setParameter("url", url)
                    .setParameter("limit", String.valueOf(cdxLimit))
                    .setParameter("sort", "reverse")
                    .build();
            log.debug("CDX query {}", uri);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(uri.toURL().openStream()))) {
                while (true) {
                    String line = br.readLine();
                    if (line == null) break;
                    String[] fields = line.split(" ");
                    Instant date = Util.ARC_DATE.parse(fields[1], Instant::from);
                    Url targetUrl = new Url(fields[2]);
                    String mime = fields[3];
                    byte[] digest = fields[5].equals("-") ? null : digestCodec.decode(fields[5]);
                    long responseOffset = Long.parseLong(fields[8]);
                    long responseLength = Long.parseLong(fields[9]);
                    int status = Integer.parseInt(fields[4]);
                    visits.add(new Visit(targetUrl.originId(), targetUrl.pathId(), date, "GET", status, mime, null, null,
                            0, 0, null, responseOffset, responseLength,
                            digest, null));
                }
            } catch (IOException e) {
                log.error("CDX query failed: " + uri, e);
            }
            return visits;
        } catch (URISyntaxException e) {
            log.error("Invalid CDX server URL", e);
            return List.of();
        }
    }
}
