package org.netpreserve.chronicrawl;

import java.io.IOException;
import java.time.format.DateTimeFormatter;

import static java.time.ZoneOffset.UTC;

public class Util {
    static DateTimeFormatter ARC_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(UTC);

    public interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }
}
