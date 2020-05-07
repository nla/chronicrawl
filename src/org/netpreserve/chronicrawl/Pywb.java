package org.netpreserve.chronicrawl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class Pywb implements Closeable {
    private final static Logger log = LoggerFactory.getLogger(Pywb.class);
    private final Process process;
    private final Path dir;
    private final Path yaml;
    private final int port;
    boolean closed = false;

    Pywb(Config config) throws IOException {
        if (config.pywb != null) {
            dir = Files.createTempDirectory("tmpywb");
            yaml = dir.resolve("config.yaml");
            this.port = config.pywbPort;
            Files.writeString(yaml, "collections:\n" +
                            "  replay:\n" +
                            "    archive_paths: http://localhost:" + config.uiPort + "/record/serve\n" +
                            "    index: cdx+http://localhost:" + config.uiPort + "/cdx\n");
            process = new ProcessBuilder(config.pywb, "-p", Integer.toString(port))
                    .directory(dir.toFile())
                    .inheritIO().start();
        } else {
            process = null;
            dir = null;
            yaml = null;
            port = 0;
        }
    }

    @Override
    public synchronized void close() {
        if (process != null && !closed) {
            closed = true;
            process.destroy();
            try {
                Files.deleteIfExists(dir.resolve("config.yaml"));
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                log.warn("Error cleaning up Pywb temp dir", e);
            }
        }
    }

    public String replayUrl(Url url, Instant date) {
        return process == null ? null : "http://localhost:" + port + "/replay/" + Util.ARC_DATE.format(date) + "/" + url;
    }
}
