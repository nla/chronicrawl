package org.netpreserve.chronicrawl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;

public class Util {
    static DateTimeFormatter ARC_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(UTC);

    public interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }

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

    static String encodeId(long x) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(toBytes(x));
    }

    static byte[] toBytes(long x) {
        return new byte[]{
                (byte) x,
                (byte) (x >> 8),
                (byte) (x >> 16),
                (byte) (x >> 24),
                (byte) (x >> 32),
                (byte) (x >> 40),
                (byte) (x >> 48),
                (byte) (x >> 56)};
    }
}
