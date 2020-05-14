package org.netpreserve.chronicrawl;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

public class Analysis {
    private final Map<String, Resource> resourceMap = new ConcurrentSkipListMap<String, Resource>();
    private final Set<Url> links = new ConcurrentSkipListSet<>();
    public Location location;
    public final Instant visitDate;
    public String title;
    public byte[] screenshot;
    public boolean hasScript;

    public Analysis(Location location, Instant visitDate) {
        this.location = location;
        this.visitDate = visitDate;
    }

    public void addResource(String method, Url url, ResourceType type, Visit visit, String analyser) {
        String ssurt = url.ssurt();
        var resource = new Resource(method, url, type, visit, analyser);
        var existing = resourceMap.putIfAbsent(ssurt, resource);
        if (existing != null) existing.analysers.add(analyser);
    }

    public Collection<Resource> resources() {
        return Collections.unmodifiableCollection(resourceMap.values());
    }

    public void addLink(Url url) {
        if (!url.scheme().equals("http") && !url.scheme().equals("https")) return;
        links.add(url.withoutFragment());
    }

    public Collection<Url> links() {
        return Collections.unmodifiableCollection(links);
    }

    public String screenshotDataUrl() {
        return Util.makeJpegDataUrl(screenshot);
    }

    public enum ResourceType {
        Document, Stylesheet, Image, Media, Font, Script, TextTrack, XHR, Fetch, EventSource, WebSocket, Manifest,
        SignedExchange, Ping, CSPViolationReport, Other
    }

    public static class Resource {
        public final String method;
        public final Url url;
        public final ResourceType type;
        public final Set<String> analysers = new ConcurrentSkipListSet<>();
        public final Visit visit;

        public Resource(String method, Url url, ResourceType type, Visit visit, String analyser) {
            this.method = method;
            this.url = url;
            this.type = type;
            this.visit = visit;
            analysers.add(analyser);
        }
    }
}
