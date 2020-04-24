package trickler;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.netpreserve.urlcanon.Canonicalizer;
import org.netpreserve.urlcanon.ParsedUrl;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URI;
import java.net.URL;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Url {
    private static final XXHash64 xxhash = XXHashFactory.fastestJavaInstance().hash64();
    private final ParsedUrl parsed;

    public Url(String url) {
        this.parsed = ParsedUrl.parseUrl(url);
        Canonicalizer.WHATWG.canonicalize(parsed);
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
        return URI.create(toString());
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

    public Socket connect() throws IOException {
        if ("http".equalsIgnoreCase(scheme())) {
            return new Socket(host(), port() < 0 ? 80 : port());
        } else if ("https".equalsIgnoreCase(scheme())) {
            return SSLSocketFactory.getDefault().createSocket(host(), port() < 0 ? 443 : port());
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + scheme());
        }
    }
}
