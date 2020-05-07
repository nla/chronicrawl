package org.netpreserve.chronicrawl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

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
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
