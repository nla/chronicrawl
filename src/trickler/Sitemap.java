package trickler;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

/**
 * Sitemap protocol parser.
 *
 * @see <a href="Sitemap protocol definition"></a>https://www.sitemaps.org/protocol.html</a>
 */
public class Sitemap {
    private static final XMLInputFactory xmlInputFactory;

    static {
        xmlInputFactory = XMLInputFactory.newInstance();
        xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlInputFactory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    }

    private static final DateTimeFormatter W3C_DATE = DateTimeFormatter.ofPattern("yyyy[-MM[-dd]]", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    public static void parse(InputStream stream, Consumer<Entry> consumer) throws XMLStreamException {
        XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(Objects.requireNonNull(stream));
        try {
            if (reader.nextTag() == START_ELEMENT) {
                if (reader.getLocalName().equals("urlset")) {
                    parseUrlsetTag(reader, consumer);
                } else if (reader.getLocalName().equals("sitemapindex")) {
                    parseSitemapIndextag(reader, consumer);
                } else {
                    throw new XMLStreamException("Invalid sitemap. Expected urlset or sitemapindex element");
                }
            }
        } finally {
            reader.close();
        }
    }

    private static void parseUrlsetTag(XMLStreamReader reader, Consumer<Entry> consumer) throws XMLStreamException {
        while (reader.nextTag() == START_ELEMENT) {
            if (reader.getLocalName().equals("url")) {
                parseEntry(reader, consumer, Location.Type.PAGE);
            } else {
                skipTag(reader);
            }
        }
    }

    private static void parseSitemapIndextag(XMLStreamReader reader, Consumer<Entry> consumer) throws XMLStreamException {
        while (reader.nextTag() == START_ELEMENT) {
            if (reader.getLocalName().equals("sitemap")) {
                parseEntry(reader, consumer, Location.Type.SITEMAP);
            } else {
                skipTag(reader);
            }
        }
    }

    private static void parseEntry(XMLStreamReader reader, Consumer<Entry> consumer, Location.Type type) throws XMLStreamException {
        String loc = null;
        ChangeFreq changefreq = null;
        Float priority = null;
        TemporalAccessor lastmod = null;
        while (reader.nextTag() == START_ELEMENT) {
            switch (reader.getLocalName()) {
                case "loc":
                    loc = reader.getElementText();
                    break;
                case "changefreq":
                    changefreq = ChangeFreq.valueOf(reader.getElementText().toUpperCase(Locale.ROOT));
                    break;
                case "priority":
                    priority = Float.parseFloat(reader.getElementText().trim());
                    break;
                case "lastmod":
                    lastmod = tryParseDate(reader.getElementText());
                    break;
                default:
                    skipTag(reader);
                    break;
            }
        }
        if (loc != null) {
            consumer.accept(new Entry(loc, type, lastmod, changefreq, priority));
        }
    }

    public static TemporalAccessor tryParseDate(String date) {
        try {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(date, ZonedDateTime::from);
        } catch (DateTimeParseException e) {
            try {
                return W3C_DATE.parseBest(date, LocalDate::from, YearMonth::from, Year::from);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static void skipTag(XMLStreamReader reader) throws XMLStreamException {
        for (int depth = 1; depth > 0; ) {
            if (reader.next() == START_ELEMENT) {
                depth++;
            } else if (reader.getEventType() == XMLStreamReader.END_ELEMENT) {
                depth--;
            }
        }
    }

    public static class Entry {
        public final String loc;
        public final Location.Type type;
        public final TemporalAccessor lastmod;
        public final ChangeFreq changefreq;
        public final Float priority;

        public Entry(String loc, Location.Type type, TemporalAccessor lastmod, ChangeFreq changefreq, Float priority) {
            this.loc = loc;
            this.type = type;
            this.lastmod = lastmod;
            this.changefreq = changefreq;
            this.priority = priority;
        }

        @Override
        public String toString() {
            return "Entry{" +
                    "loc='" + loc + '\'' +
                    ", type=" + type +
                    ", lastmod=" + lastmod +
                    ", changefreq=" + changefreq +
                    ", priority=" + priority +
                    '}';
        }
    }

    public enum ChangeFreq {
        ALWAYS, HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY, NEVER
    }
}
