package org.netpreserve.chronicrawl;

import com.steadystate.css.parser.HandlerBase;
import com.steadystate.css.parser.SACParserCSS3;
import org.jsoup.Jsoup;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcTargetRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.css.sac.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.netpreserve.chronicrawl.Location.Type.TRANSCLUSION;

public class Analysis {
    private static final Logger log = LoggerFactory.getLogger(Analysis.class);
    private static final Pattern SRCSET = Pattern.compile("[\\s,]*(\\S*[^,\\s])(?:\\s(?:[^,(]+|\\([^)]*(?:\\)|$))*)?", Pattern.MULTILINE);
    private static final Pattern META_REFRESH = Pattern.compile("\\d+\\s*;\\s*url=['\"]?(.*?)['\"]?");
    private static final boolean extraAttrs = true;

    private final Map<String, Resource> resourceMap = new ConcurrentSkipListMap<>();
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

    public Analysis(Crawl crawl, Location location, Instant date, boolean recordMode) throws IOException {
        this.location = location;
        visitDate = date;
        Visit visit = crawl.db.visits.find(location.originId, location.pathId, date);
        crawl.storage.readResponse(visit, this::parseHtml);
        if (hasScript) {
            browse(crawl, recordMode);
        }
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

    @SuppressWarnings("unused")
    public enum ResourceType {
        Document, Stylesheet, Image, Media, Font, Script, TextTrack, XHR, Fetch, EventSource, WebSocket, Manifest,
        SignedExchange, Ping, CSPViolationReport, Other
    }

    public static class Resource {
        public final String method;
        public final Url url;
        public final ResourceType type;
        public final Set<String> analysers = new ConcurrentSkipListSet<>();
        public Visit visit;

        public Resource(String method, Url url, ResourceType type, Visit visit, String analyser) {
            this.method = method;
            this.url = url;
            this.type = type;
            this.visit = visit;
            analysers.add(analyser);
        }
    }

    void browse(Crawl crawl, boolean recordMode) {
        try (Browser.Tab tab = crawl.browser.createTab()) {
            if (crawl.config.scriptDeterminism) tab.overrideDateAndRandom(visitDate);
            tab.interceptRequests(request -> onRequestIntercepted(request, crawl, recordMode));
            try {
                tab.navigate(location.url.toString()).get(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
                throw new RuntimeException(e.getCause());
            } catch (TimeoutException e) {
                log.warn("Timed out waiting for page load {}", location.url);
            }

            screenshot = tab.screenshot();

            tab.scrollDown();

            tab.extractLinks().forEach(link -> addLink(new Url(link)));
            title = tab.title();
        }
    }

    private void onRequestIntercepted(Browser.Request request, Crawl crawl, boolean recordMode) {
        try {
            Url subUrl = new Url(request.url());
            Visit subvisit = crawl.db.visits.findClosest(subUrl.originId(), subUrl.pathId(), visitDate, request.method());
            ResourceType type = ResourceType.valueOf(request.resourceType);
            addResource(request.method(), subUrl, type, subvisit, "browser");
            if (subvisit == null) {
                if (!recordMode) {
                    request.fail("InternetDisconnected");
                    return;
                }
                crawl.enqueue(location, Instant.now(), subUrl, TRANSCLUSION);
                Origin origin = crawl.db.origins.find(subUrl.originId());
                if (origin.crawlPolicy == CrawlPolicy.FORBIDDEN) {
                    log.trace("Subresource forbidden by crawl policy: {}", subUrl);
                    request.fail("AccessDenied");
                    return;
                }
                Location location = crawl.db.locations.find(subUrl.originId(), subUrl.pathId());
                Objects.requireNonNull(location);
                try (Exchange subexchange = new Exchange(crawl, origin, location, request.method(), request.headers())) {
                    subexchange.run();
                    if (subexchange.revisitOf != null) {
                        subvisit = subexchange.revisitOf;
                    } else {
                        subvisit = crawl.db.visits.find(subUrl.originId(), subUrl.pathId(), subexchange.date);
                    }
                }
            }
            if (subvisit.status < 0) {
                request.fail("Failed");
            } else {
                crawl.storage.readResponse(subvisit, (rec, rsp) -> request.fulfill(rsp));
            }
        } catch (IOException e) {
            request.fail("Failed");
        }
    }

    public void parseHtml(InputStream stream, String charset, String url) throws IOException {
        if (charset != null && !Charset.isSupported(charset)) charset = null;
        Document doc = Jsoup.parse(stream, charset, url);
        doc.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (!(node instanceof Element)) return;
                Element element = (Element) node;
                String style = node.attr("style");
                if (!style.isBlank()) {
                    parseStyleAttribute(style, node.baseUri());
                }
                switch (element.tagName()) {
                    case "a":
                    case "area":
                        addLink(node.attr("abs:href"));
                        break;
                    case "audio":
                    case "track":
                        addResource(node.attr("abs:src"), ResourceType.Media);
                        break;
                    case "command":
                        addResource(node.attr("abs:icon"), ResourceType.Image);
                        break;
                    case "frame":
                        addResource(node.attr("abs:src"), ResourceType.Document);
                        break;
                    case "img":
                    case "source":
                        ResourceType type = ResourceType.Image;
                        if (element.tagName().equals("source") && element.parent() != null) {
                            String parentTag = element.parent().tagName();
                            if (parentTag.equals("audio") || parentTag.equals("video")) {
                                type = ResourceType.Media;
                            }
                        }
                        addResource(node.attr("abs:src"), type);
                        for (Matcher m = SRCSET.matcher(node.attr("srcset")); m.lookingAt(); m.region(m.end(), m.regionEnd())) {
                            addResource(StringUtil.resolve(node.baseUri(), m.group(1)), ResourceType.Image);
                        }
                        if (extraAttrs) {
                            addResource(node.attr("abs:data-src"), type);
                            for (Matcher m = SRCSET.matcher(node.attr("data-srcset")); m.lookingAt(); m.region(m.end(), m.regionEnd())) {
                                addResource(StringUtil.resolve(node.baseUri(), m.group(1)), ResourceType.Image);
                            }
                        }

                        break;
                    case "link":
                        if ("stylesheet".equalsIgnoreCase(node.attr("rel"))) {
                            addResource(node.attr("abs:href"), ResourceType.Stylesheet);
                        }
                        break;
                    case "meta":
                        if (node.attr("http-equiv").equalsIgnoreCase("refresh")) {
                            Matcher m = META_REFRESH.matcher(node.attr("content"));
                            if (m.matches()) {
                                addResource(StringUtil.resolve(node.baseUri(), m.group(1)), ResourceType.Document);
                            }
                        }
                        break;
                    case "input":
                        addResource(node.attr("abs:src"), ResourceType.Image);
                        break;
                    case "script":
                        hasScript = true;
                        addResource(node.attr("abs:src"), ResourceType.Script);
                        break;
                    case "style":
                        parseStyleSheet(element.html(), url);
                        break;
                    case "title":
                        if (title == null) title = element.text();
                        break;
                    case "video":
                        addResource(node.attr("abs:poster"), ResourceType.Image);
                        addResource(node.attr("abs:src"), ResourceType.Media);
                        break;
                }
            }

            @Override
            public void tail(Node node, int depth) {
            }
        });
    }

    private void addLink(String url) {
        if (url.isBlank()) return;
        addLink(new Url(url));
    }

    private void parseStyleSheet(String value, String baseUrl) {
        InputSource source = new InputSource();
        source.setURI(baseUrl);
        source.setCharacterStream(new StringReader(value));
        SACParserCSS3 parser = new SACParserCSS3();
        parser.setDocumentHandler(new StyleHandler(baseUrl));
        try {
            parser.parseStyleSheet(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void parseStyleAttribute(String value, String baseUrl) {
        InputSource source = new InputSource();
        source.setURI(baseUrl);
        source.setCharacterStream(new StringReader(value));
        SACParserCSS3 parser = new SACParserCSS3();
        parser.setDocumentHandler(new StyleHandler(baseUrl));
        try {
            parser.parseStyleDeclaration(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void parseHtml(WarcTargetRecord record, WarcResponse response) throws IOException {
        parseHtml(response.http().body().stream(), response.http().contentType().parameters().get("charset"),
                record.target());
    }

    private class StyleHandler extends HandlerBase {
        private final String baseUrl;
        private ResourceType type = ResourceType.Image;

        public StyleHandler(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        public void importStyle(String uri, SACMediaList media, String defaultNamespaceURI, Locator locator) throws CSSException {
            addResource(uri, ResourceType.Stylesheet);
        }

        @Override
        public void startFontFace(Locator locator) throws CSSException {
            this.type = ResourceType.Font;
        }

        @Override
        public void endFontFace() throws CSSException {
            this.type = ResourceType.Image;
        }

        @Override
        public void property(String name, LexicalUnit value, boolean important, Locator locator) {
            for (var unit = value; unit != null; unit = unit.getNextLexicalUnit()) {
                if (unit.getLexicalUnitType() == LexicalUnit.SAC_URI) {
                    addResource(StringUtil.resolve(baseUrl, unit.getStringValue()), type);
                }
            }
        }
    }

    private void addResource(String url, ResourceType type) {
        if (url == null || url.isBlank()) return;
        addResource("GET", new Url(url), type, null, "classic");
    }
}
