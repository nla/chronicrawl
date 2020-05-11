package org.netpreserve.chronicrawl;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.netpreserve.urlcanon.Canonicalizer;
import org.netpreserve.urlcanon.ParsedUrl;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Url implements Comparable<Url> {
    private static final XXHash64 xxhash = XXHashFactory.fastestJavaInstance().hash64();
    private final ParsedUrl parsed;
    private String ssurt;

    public Url(String url) {
        this.parsed = ParsedUrl.parseUrl(url);
        Canonicalizer.WHATWG.canonicalize(parsed);
    }

    private Url(ParsedUrl parsed) {
        this.parsed = parsed;
    }

    public long id() {
        return hash(toString());
    }

    public long originId() {
        return hash(origin());
    }

    public String origin() {
        return parsed.getScheme() + parsed.getColonAfterScheme() + parsed.getSlashes() + parsed.getHost() +
                parsed.getColonBeforePort() + parsed.getPort();
    }

    public String toString() {
        return parsed.toString();
    }

    private static long hash(String url) {
        byte[] data = url.getBytes(UTF_8);
        return xxhash.hash(data, 0, data.length, 0);
    }

    public Url resolve(String other) {
        return new Url(URI.create(toString()).resolve(other).toString());
    }

    public String path() {
        return parsed.getPath();
    }

    public URL toURL() {
        try {
            return new URL(toString());
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
    }

    public URI toURI() {
        try {
            return new URI(parsed.getScheme(), null,
                    parsed.getHost(), parsed.getPort().isBlank() ? -1 : Integer.parseInt(parsed.getPort()), parsed.getPath(),
                    parsed.getQuestionMark().isEmpty() ? null : parsed.getQuery(),
                    parsed.getHashSign().isEmpty() ? null : parsed.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public String scheme() {
        return parsed.getScheme();
    }

    public String host() {
        return parsed.getHost();
    }

    public int port() {
        return !parsed.getPort().isBlank() ? Integer.parseInt(parsed.getPort()) : -1;
    }

    public String target() {
        return parsed.getPath() + parsed.getQuestionMark() + parsed.getQuery();
    }

    public String hostInfo() {
        return parsed.getHost() + parsed.getColonBeforePort() + parsed.getPort();
    }

    public Socket connect(InetAddress bindAddress) throws IOException {
        if ("http".equalsIgnoreCase(scheme())) {
            return new Socket(host(), port() < 0 ? 80 : port(), bindAddress, 0);
        } else if ("https".equalsIgnoreCase(scheme())) {
            return SSLSocketFactory.getDefault().createSocket(host(), port() < 0 ? 443 : port());
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + scheme());
        }
    }

    public Url withScheme(String scheme) {
        ParsedUrl copy = new ParsedUrl(parsed);
        copy.setScheme(scheme);
        return new Url(copy);
    }

    public Url withoutFragment(){
        ParsedUrl copy = new ParsedUrl(parsed);
        copy.setFragment("");
        copy.setHashSign("");
        return new Url(copy);
    }

    String ssurt() {
        if (ssurt == null) {
            ssurt = parsed.ssurt();
        }
        return ssurt;
    }

    @Override
    public int compareTo(Url o) {
        return ssurt().compareTo(o.ssurt());
    }
}
