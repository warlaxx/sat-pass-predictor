package space.nextpass.separations;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import space.nextpass.separations.Separations.Date;
import space.nextpass.separations.Separations.FeedEntry;
import space.nextpass.separations.Separations.Kind;

/**
 * The newest separations as an RSS 2.0 feed (ABD-15), in English only: the owner chose
 * one feed, without a French twin.
 *
 * <p>An item is an event, never a fragment, and links to the event's page. Its title is
 * the page's {@code <h1>}, word for word: the four templates below are copies of the
 * English ones in {@code frontend/src/app/pages/separation/separation.ts} ({@code heading}),
 * and a change to either side must be made to the other.
 *
 * <p>The data's source is credited once, in the channel's {@code <copyright>}, with the
 * text of the separation list's footer; no item names it.
 *
 * <p>Everything goes through the JDK's StAX writer, which escapes names such as
 * {@code R&D-1}: nothing is concatenated into XML.
 */
public final class SeparationFeed {

    /** Items in the feed. */
    public static final int ENTRIES = 50;

    static final String TITLE = "NextPass · New separations in orbit";
    static final String DESCRIPTION = "Objects released by other objects in orbit, and breakups,"
            + " as soon as NextPass learns of them.";
    /** The footer of {@code /separations}, word for word. */
    static final String CREDIT = "Separation data: GCAT, Jonathan C. McDowell, planet4589.org — CC BY 4.0.";

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("d MMMM uuuu 'at' HH:mm 'UTC'", Locale.ENGLISH);
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("d MMMM uuuu 'at' HH:mm:ss 'UTC'", Locale.ENGLISH);

    private final String siteUrl;

    /** @param siteUrl the public site, without a trailing slash */
    public SeparationFeed(String siteUrl) {
        this.siteUrl = siteUrl;
    }

    /**
     * The feed, encoded in UTF-8.
     *
     * @param updatedAt the last import, the channel's {@code lastBuildDate}; null before the first
     */
    public byte[] write(List<FeedEntry> entries, Instant updatedAt) {
        var out = new ByteArrayOutputStream();
        try {
            // A factory per feed: the API does not promise a shared one is thread-safe.
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("rss");
            xml.writeAttribute("version", "2.0");
            xml.writeNamespace("atom", ATOM);
            xml.writeStartElement("channel");
            element(xml, "title", TITLE);
            element(xml, "link", siteUrl + "/separations");
            element(xml, "description", DESCRIPTION);
            element(xml, "language", "en");
            element(xml, "copyright", CREDIT);
            if (updatedAt != null) {
                element(xml, "lastBuildDate", rfc822(updatedAt));
            }
            xml.writeEmptyElement("atom", "link", ATOM);
            xml.writeAttribute("href", siteUrl + "/api/separations/feed.xml");
            xml.writeAttribute("rel", "self");
            xml.writeAttribute("type", "application/rss+xml");
            for (FeedEntry entry : entries) {
                String link = siteUrl + "/separations/" + entry.id();
                xml.writeStartElement("item");
                element(xml, "title", title(entry));
                element(xml, "link", link);
                xml.writeStartElement("guid");
                xml.writeAttribute("isPermaLink", "true");
                xml.writeCharacters(link);
                xml.writeEndElement();
                element(xml, "pubDate", rfc822(entry.publishedAt()));
                element(xml, "description", description(entry));
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not write the separations feed", e);
        }
        return out.toByteArray();
    }

    /**
     * The event page's {@code <h1>}. The parent falls back to its identifier, as on the
     * page; a release names its object only when it released one.
     */
    static String title(FeedEntry entry) {
        String parent = entry.parentName() != null ? entry.parentName()
                : entry.parentId() != null ? entry.parentId() : "";
        if (entry.kind() == Kind.FRAGMENTATION) {
            return entry.children() == 1
                    ? "A fragment separated from " + parent
                    : entry.children() + " fragments separated from " + parent;
        }
        return entry.children() == 1
                ? parent + " released " + (entry.firstChildName() != null ? entry.firstChildName() : entry.id())
                : parent + " released " + entry.children() + " objects";
    }

    /** "Recorded as September 2026, uncertain. 62 objects." */
    static String description(FeedEntry entry) {
        Date date = entry.date();
        return "Recorded as " + dateLabel(date) + (date.uncertain() ? ", uncertain" : "") + ". "
                + entry.children() + (entry.children() == 1 ? " object." : " objects.");
    }

    /**
     * A recorded date with exactly its own precision, as {@code recordedDateLabel} reads it
     * on the pages ({@code frontend/src/app/pages/separations/separation-format.ts}):
     * {@code 2026 Sep?} is "September 2026", never the first of September. Times are UTC
     * and say so.
     */
    static String dateLabel(Date date) {
        if (date.at() == null || date.precision() == null) {
            return date.text();
        }
        ZonedDateTime at = date.at().atZone(ZoneOffset.UTC);
        return switch (date.precision()) {
            case "DECADE" -> at.getYear() + "s";
            case "YEAR" -> String.valueOf(at.getYear());
            case "QUARTER" -> "Q" + ((at.getMonthValue() - 1) / 3 + 1) + " " + at.getYear();
            case "MONTH" -> MONTH.format(at);
            case "DAY" -> DAY.format(at);
            case "MINUTE" -> MINUTE.format(at);
            case "SECOND" -> SECOND.format(at);
            default -> date.text();
        };
    }

    /** RSS dates: RFC 822, as RFC 1123 writes them ({@code Tue, 1 Sep 2026 00:00:00 GMT}). */
    private static String rfc822(Instant instant) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC));
    }

    private static void element(XMLStreamWriter xml, String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(text);
        xml.writeEndElement();
    }
}
