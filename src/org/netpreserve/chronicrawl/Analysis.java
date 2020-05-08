package org.netpreserve.chronicrawl;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

public class Analysis {
    private final Map<String, Resource> resourceMap = new ConcurrentSkipListMap<String, Resource>();
    public final Set<Url> links = new ConcurrentSkipListSet<>();
    public final Url url;
    public final Instant date;
    public String title;
    public String screenshot;
    public boolean hasScript;

    public Analysis(Url url, Instant date) {
        this.url = url;
        this.date = date;
    }

    public void addResource(String method, Url url, ResourceType type, UUID responseId, String analyser) {
        String ssurt = url.ssurt();
        var resource = new Resource(method, url, type, responseId, analyser);
        var existing = resourceMap.putIfAbsent(ssurt, resource);
        if (existing != null) existing.analysers.add(analyser);
    }

    public Collection<Resource> resources() {
        return Collections.unmodifiableCollection(resourceMap.values());
    }

    public enum ResourceType {
        Document, Stylesheet, Image, Media, Font, Script, TextTrack, XHR, Fetch, EventSource, WebSocket, Manifest,
        SignedExchange, Ping, CSPViolationReport, Other
    }

    public static class Resource {
        public final String method;
        public final Url url;
        public final ResourceType type;
        public final UUID responseId;
        public final Set<String> analysers = new ConcurrentSkipListSet<>();

        public Resource(String method, Url url, ResourceType type, UUID responseId, String analyser) {
            this.method = method;
            this.url = url;
            this.type = type;
            this.responseId = responseId;
            analysers.add(analyser);
        }
    }
}
