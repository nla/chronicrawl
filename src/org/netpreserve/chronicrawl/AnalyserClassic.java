package org.netpreserve.chronicrawl;

import com.steadystate.css.parser.HandlerBase;
import com.steadystate.css.parser.SACParserCSS3;
import org.jsoup.Jsoup;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;
import org.netpreserve.chronicrawl.Analysis.ResourceType;
import org.netpreserve.jwarc.WarcResponse;
import org.w3c.css.sac.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnalyserClassic {
    private static final Pattern SRCSET = Pattern.compile("[\\s,]*(\\S*[^,\\s])(?:\\s(?:[^,(]+|\\([^)]*(?:\\)|$))*)?", Pattern.MULTILINE);
    private static final Pattern META_REFRESH = Pattern.compile("\\d+\\s*;\\s*url=['\"]?(.*?)['\"]?");
    private final Analysis analysis;
    private boolean extraAttrs = true;

    public AnalyserClassic(Analysis analysis) {
        this.analysis = analysis;
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
                        analysis.hasScript = true;
                        addResource(node.attr("abs:src"), ResourceType.Script);
                        break;
                    case "style":
                        parseStyleSheet(element.html(), url);
                        break;
                    case "title":
                        if (analysis.title == null) analysis.title = element.text();
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
        analysis.addLink(new Url(url));
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

    public void parseHtml(WarcResponse response) throws IOException {
        parseHtml(response.http().body().stream(), response.http().contentType().parameters().get("charset"), response.target());
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
        analysis.addResource("GET", new Url(url), type, null, "classic");
    }
}
