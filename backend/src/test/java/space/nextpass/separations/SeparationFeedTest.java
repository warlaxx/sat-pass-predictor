package space.nextpass.separations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import space.nextpass.separations.Separations.Date;
import space.nextpass.separations.Separations.FeedEntry;
import space.nextpass.separations.Separations.Kind;

class SeparationFeedTest {

    static final Date SEP_2026 = new Date("2026 Sep?", Instant.parse("2026-09-01T00:00:00Z"), "MONTH", true);
    static final Instant SEEN = Instant.parse("2026-10-08T03:12:00Z");

    final SeparationFeed feed = new SeparationFeed("https://www.nextpass.space");

    static FeedEntry entry(String id, Kind kind, String parentName, String parentId, String child, int children) {
        return new FeedEntry(id, kind, SEP_2026, parentName, parentId, child, children, SEEN);
    }

    /** The four headings of the event page, in the same order as in separation.ts. */
    @Test
    void titlesAreTheEventPageHeadings() {
        assertThat(SeparationFeed.title(entry("S100685", Kind.RELEASE, "USA 396", "S60322", "USA 667", 1)))
                .isEqualTo("USA 396 released USA 667");
        assertThat(SeparationFeed.title(entry("S100685", Kind.RELEASE, "USA 396", "S60322", null, 1)))
                .isEqualTo("USA 396 released S100685");
        assertThat(SeparationFeed.title(entry("S100700", Kind.RELEASE, "Transporter-15", "A11696", "FRG-10D1", 3)))
                .isEqualTo("Transporter-15 released 3 objects");
        assertThat(SeparationFeed.title(entry("S100810", Kind.FRAGMENTATION, "Yaogan 50", "S66000", "Yaogan 50 DEB", 62)))
                .isEqualTo("62 fragments separated from Yaogan 50");
        assertThat(SeparationFeed.title(entry("S69731", Kind.FRAGMENTATION, null, "S12345", "DEB", 1)))
                .isEqualTo("A fragment separated from S12345");
    }

    @Test
    void datesKeepTheirOwnPrecisionAndTimesSayUtc() {
        assertThat(SeparationFeed.dateLabel(new Date("1960s?", Instant.parse("1960-01-01T00:00:00Z"), "DECADE", true)))
                .isEqualTo("1960s");
        assertThat(SeparationFeed.dateLabel(new Date("1995", Instant.parse("1995-01-01T00:00:00Z"), "YEAR", false)))
                .isEqualTo("1995");
        assertThat(SeparationFeed.dateLabel(new Date("2026 Q3?", Instant.parse("2026-07-01T00:00:00Z"), "QUARTER", true)))
                .isEqualTo("Q3 2026");
        assertThat(SeparationFeed.dateLabel(SEP_2026)).isEqualTo("September 2026");
        assertThat(SeparationFeed.dateLabel(new Date("2026 Jul 20?", Instant.parse("2026-07-20T00:00:00Z"), "DAY", true)))
                .isEqualTo("20 July 2026");
        assertThat(SeparationFeed.dateLabel(new Date("2026 May 29 1120", Instant.parse("2026-05-29T11:20:00Z"), "MINUTE", false)))
                .isEqualTo("29 May 2026 at 11:20 UTC");
        assertThat(SeparationFeed.dateLabel(new Date("2026 May 29 112005", Instant.parse("2026-05-29T11:20:05Z"), "SECOND", false)))
                .isEqualTo("29 May 2026 at 11:20:05 UTC");
        assertThat(SeparationFeed.dateLabel(new Date("soon", null, null, false))).isEqualTo("soon");
    }

    @Test
    void theDescriptionGivesTheDateItsPrecisionAndTheCount() {
        assertThat(SeparationFeed.description(entry("S100685", Kind.RELEASE, "USA 396", "S60322", "USA 667", 1)))
                .isEqualTo("Recorded as September 2026, uncertain. 1 object.");
        assertThat(SeparationFeed.description(new FeedEntry("S100810", Kind.FRAGMENTATION,
                new Date("2026 May 29 1120", Instant.parse("2026-05-29T11:20:00Z"), "MINUTE", false),
                "Yaogan 50", "S66000", null, 62, SEEN)))
                .isEqualTo("Recorded as 29 May 2026 at 11:20 UTC. 62 objects.");
    }

    @Test
    void writesAWellFormedRss2Channel() throws Exception {
        Document rss = parse(feed.write(List.of(
                entry("S100685", Kind.RELEASE, "USA 396", "S60322", "USA 667", 1)),
                Instant.parse("2026-10-08T03:15:00Z")));

        assertThat(text(rss, "/rss/@version")).isEqualTo("2.0");
        assertThat(text(rss, "/rss/channel/title")).isEqualTo("NextPass · New separations in orbit");
        assertThat(text(rss, "/rss/channel/link")).isEqualTo("https://www.nextpass.space/separations");
        assertThat(text(rss, "/rss/channel/description")).isNotBlank();
        assertThat(text(rss, "/rss/channel/language")).isEqualTo("en");
        assertThat(text(rss, "/rss/channel/lastBuildDate")).isEqualTo("Thu, 8 Oct 2026 03:15:00 GMT");
        assertThat(text(rss, "/rss/channel/*[local-name()='link' and namespace-uri()='http://www.w3.org/2005/Atom']/@href"))
                .isEqualTo("https://www.nextpass.space/api/separations/feed.xml");
        assertThat(text(rss, "/rss/channel/*[namespace-uri()='http://www.w3.org/2005/Atom']/@rel")).isEqualTo("self");
        assertThat(text(rss, "/rss/channel/*[namespace-uri()='http://www.w3.org/2005/Atom']/@type"))
                .isEqualTo("application/rss+xml");

        assertThat(text(rss, "/rss/channel/item[1]/title")).isEqualTo("USA 396 released USA 667");
        assertThat(text(rss, "/rss/channel/item[1]/link")).isEqualTo("https://www.nextpass.space/separations/S100685");
        assertThat(text(rss, "/rss/channel/item[1]/guid")).isEqualTo("https://www.nextpass.space/separations/S100685");
        assertThat(text(rss, "/rss/channel/item[1]/guid/@isPermaLink")).isEqualTo("true");
        assertThat(text(rss, "/rss/channel/item[1]/pubDate")).isEqualTo("Thu, 8 Oct 2026 03:12:00 GMT");
        assertThat(text(rss, "/rss/channel/item[1]/description"))
                .isEqualTo("Recorded as September 2026, uncertain. 1 object.");
    }

    @Test
    void theSourceIsCreditedOnceInTheChannelAndNeverInAnItem() throws Exception {
        byte[] body = feed.write(List.of(
                entry("S100685", Kind.RELEASE, "USA 396", "S60322", "USA 667", 1),
                entry("S100810", Kind.FRAGMENTATION, "Yaogan 50", "S66000", null, 62)), null);
        Document rss = parse(body);
        String xml = new String(body, StandardCharsets.UTF_8);

        assertThat(text(rss, "/rss/channel/copyright"))
                .isEqualTo("Separation data: GCAT, Jonathan C. McDowell, planet4589.org — CC BY 4.0.");
        assertThat(xml.split("GCAT", -1)).hasSize(2);
        assertThat(xml.split("McDowell", -1)).hasSize(2);
        NodeList items = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate("/rss/channel/item", rss, XPathConstants.NODESET);
        assertThat(items.getLength()).isEqualTo(2);
        for (int i = 0; i < items.getLength(); i++) {
            assertThat(items.item(i).getTextContent()).doesNotContain("GCAT").doesNotContain("McDowell");
        }
        // Before the first import there is no build date to give, rather than a wrong one.
        assertThat(text(rss, "count(/rss/channel/lastBuildDate)")).isEqualTo("0");
    }

    @Test
    void namesAreEscapedNotConcatenated() throws Exception {
        byte[] body = feed.write(List.of(
                entry("S1", Kind.RELEASE, "R&D <\"1\">", "S2", "A & B", 1)), null);

        assertThat(new String(body, StandardCharsets.UTF_8)).contains("R&amp;D &lt;");
        assertThat(text(parse(body), "/rss/channel/item[1]/title")).isEqualTo("R&D <\"1\"> released A & B");
    }

    @Test
    void anEmptyFeedIsStillAChannel() throws Exception {
        Document rss = parse(feed.write(List.of(), null));

        assertThat(text(rss, "/rss/channel/title")).isEqualTo("NextPass · New separations in orbit");
        assertThat(text(rss, "count(/rss/channel/item)")).isEqualTo("0");
    }

    static Document parse(byte[] xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    static String text(Document document, String xpath) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(xpath, document);
    }
}
