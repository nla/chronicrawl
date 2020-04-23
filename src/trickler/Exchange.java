package trickler;

import org.netpreserve.jwarc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static java.nio.file.StandardOpenOption.*;

public class Exchange {
    private static final Logger log = LoggerFactory.getLogger(Exchange.class);
    private final Database db;
    private final WarcWriter warcWriter;
    private final Origin origin;
    private final Location location;
    private final FileChannel bufferFile;
    private final Instant startTime = Instant.now();
    private final Url url;
    private Instant fetchCompleteTime;
    private HttpRequest httpRequest;
    private HttpResponse httpResponse;
    private InetAddress ip;

    public Exchange(Origin origin, Location location, Database db, WarcWriter warcWriter) throws IOException {
        this.origin = origin;
        this.location = location;
        this.db = db;
        this.warcWriter = warcWriter;
        Path tempFile = Files.createTempFile("trickler", ".tmp");
        bufferFile = FileChannel.open(tempFile, READ, WRITE, DELETE_ON_CLOSE, TRUNCATE_EXISTING);
        url = location.url();
    }

    public void run() throws IOException {
        prepare();
        fetch();
        process();
        save();
        finish();
    }

    void prepare() {
        httpRequest = new HttpRequest.Builder("GET", url.target())
                .addHeader("Host", url.hostInfo())
                .addHeader("User-Agent", "trickler")
                .addHeader("Connection", "close")
                .version(MessageVersion.HTTP_1_0).build();
    }

    void fetch() throws IOException {
        try (Socket socket = url.connect()) {
            ip = ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress();
            socket.getOutputStream().write(httpRequest.serializeHeader());
            socket.getInputStream().transferTo(Channels.newOutputStream(bufferFile));
        } finally {
            fetchCompleteTime = Instant.now();
        }
    }

    private void process() throws IOException {
        bufferFile.position(0);
        httpResponse = HttpResponse.parse(bufferFile);
        try {
            switch (location.type()) {
                case ROBOTS:
                    processRobots();
                    break;
                case SITEMAP:
                    processSitemap();
                    break;
            }
        } catch (Exception e) {
            log.warn("Processing " + url + " failed", e);
        }
    }

    private void processRobots() throws IOException {
        Robots.parse(httpResponse.body().stream(), new Robots.Handler() {
            public void crawlDelay(long crawlDelay) {
                if (crawlDelay >= 0 && crawlDelay < Short.MAX_VALUE) {
                    db.updateOriginRobotsCrawlDelay(location.url().originId(), (short)crawlDelay);
                }
            }
            public void sitemap(String url) {
                db.tryInsertLocation(location.url().resolve(url), LocationType.SITEMAP, location.url().id(), startTime, 2);
            }
        });
    }

    private void processSitemap() throws XMLStreamException, IOException {
        Sitemap.parse(httpResponse.body().stream(), entry -> {
            Url url = location.url().resolve(entry.loc);
            db.tryInsertLocation(url, entry.type, location.url().id(), startTime, entry.type == LocationType.SITEMAP ? 3 : 10);
            db.updateLocationSitemapData(url.id(), entry.changefreq, entry.priority, entry.lastmod == null ? null : entry.lastmod.toString());
        });
    }

    void save() throws IOException {
        bufferFile.position(0);
        WarcRequest request = new WarcRequest.Builder(url.toURI())
                .date(startTime)
                .body(httpRequest)
                .ipAddress(ip)
                .build();
        WarcResponse response = new WarcResponse.Builder(url.toURI())
                .date(startTime)
                .body(MediaType.HTTP_RESPONSE, bufferFile, bufferFile.size())
                .concurrentTo(request.id())
                .ipAddress(ip)
                .build();
        warcWriter.write(request);
        warcWriter.write(response);
    }

    private void finish() {
        db.updateOriginVisit(origin.id(), startTime, origin.calculateNextVisit(startTime));
        db.updateLocationVisit(location.url().id(), startTime, startTime.plus(Duration.ofDays(1)));
    }
}
