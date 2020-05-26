package org.netpreserve.chronicrawl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;

public class Util {
    static DateTimeFormatter ARC_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(UTC);

    static String resource(String resource) {
        InputStream stream = Util.class.getResourceAsStream(resource);
        if (stream == null) throw new RuntimeException("Resource not found on classpath: " + resource);
        try (stream) {
            return new String(stream.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String makeJpegDataUrl(byte[] data) {
        return data == null ? null : "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(data);
    }

}
