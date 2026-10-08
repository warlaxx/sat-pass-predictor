package space.nextpass.separations;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import space.nextpass.gcat.GcatObject;
import space.nextpass.gcat.GcatParser;
import space.nextpass.gcat.GcatRepository;
import space.nextpass.gcat.GcatSource;
import space.nextpass.separations.Separations.Kind;

/**
 * Real PostgreSQL, real GCAT rows (src/test/resources/gcat): the grouping into events is
 * SQL, and it is the part of the separation pages most likely to be wrong.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeparationRepositoryPostgresTest {

    private static final Instant IMPORTED = Instant.parse("2026-10-02T18:23:00Z");

    HikariDataSource source;
    JdbcTemplate jdbc;
    String schema;
    SeparationRepository separations;

    @BeforeAll
    void open() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "nextpass"));
        config.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        source.close();
        config.setSchema(schema);
        source = new HikariDataSource(config);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = new JdbcTemplate(source);

        GcatRepository gcat = new GcatRepository(jdbc);
        for (String file : List.of("satcat.tsv", "satcat100k.tsv", "families.tsv", "lineage.tsv", "auxcat.tsv")) {
            List<GcatObject> objects = new ArrayList<>();
            try (var reader = new BufferedReader(new InputStreamReader(
                    getClass().getResourceAsStream("/gcat/" + file), StandardCharsets.UTF_8))) {
                GcatParser.parse(reader, objects::add);
            }
            gcat.upsert(objects, IMPORTED);
            gcat.saveFile(file, new GcatSource.Validators("\"v1\"", null), objects.size(), IMPORTED);
        }
        // As the importer does once both files are in: a row's parent may be in another file.
        gcat.reclassify(IMPORTED);
        separations = new SeparationRepository(new JdbcTemplate(source));
    }

    @AfterAll
    void close() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        source.close();
    }

    @Test
    void theNewestEventsComeFirstWithTheirParent() {
        List<Separations.Summary> latest = separations.latest(null, 3);

        assertThat(latest).extracting(Separations.Summary::id).containsExactly("S100643", "S100685", "S100810");
        Separations.Summary usa667 = latest.get(1);
        assertThat(usa667.kind()).isEqualTo(Kind.RELEASE);
        assertThat(usa667.parentName()).isEqualTo("USA 396");
        assertThat(usa667.firstChildName()).isEqualTo("USA 667");
        assertThat(usa667.firstChildNoradId()).isEqualTo(100685);
        assertThat(usa667.date()).isEqualTo(new Separations.Date(
                "2026 Sep?", Instant.parse("2026-09-01T00:00:00Z"), "MONTH", true));
        assertThat(usa667.orbit().orbitClass()).isEqualTo("GEO/D");
        assertThat(usa667.inOrbit()).isTrue();
    }

    @Test
    void kindsSplitReleasesFromFragmentations() {
        assertThat(separations.latest(Kind.FRAGMENTATION, 10)).extracting(Separations.Summary::id)
                .containsExactly("S100810", "S69731");
        assertThat(separations.latest(Kind.RELEASE, 10)).extracting(Separations.Summary::kind)
                .containsOnly(Kind.RELEASE)
                // S03600 left Kosmos-249 on its launch day: a launch, not a separation; and
                // A11846, a Dragon trunk, is in the auxiliary catalogue, which is not listed.
                .hasSize(7);
    }

    @Test
    void statsCountThisYearByMonthUpToNow() {
        Separations.Stats stats = separations.stats(Instant.parse("2026-10-02T12:00:00Z"));

        assertThat(stats.year()).isEqualTo(2026);
        assertThat(stats.byMonth()).hasSize(10);
        assertThat(stats.byMonth().get(4)).isEqualTo(new Separations.Month("2026-05", 2, 1));
        assertThat(stats.byMonth().get(7)).isEqualTo(new Separations.Month("2026-08", 0, 0));
        assertThat(stats.byMonth().get(8)).isEqualTo(new Separations.Month("2026-09", 2, 0));
    }

    @Test
    void anEventIsFoundWithItsLineageAndEvidence() {
        Separations.Event event = separations.event("S100685").orElseThrow();

        assertThat(event.id()).isEqualTo("S100685");
        assertThat(event.parent().name()).isEqualTo("USA 396");
        assertThat(event.grandparent().name()).isEqualTo("Centaur AV-101");
        assertThat(event.grandparent().role()).isEqualTo("rocket-stage");
        assertThat(event.childCount()).isEqualTo(1);
        Separations.SpaceObject child = event.children().getFirst();
        assertThat(child.payloadName()).isEqualTo("[USSF GEO Patrol 17]");
        assertThat(child.evidence().parent()).isEqualTo("S60322");
        assertThat(child.evidence().separationDate()).isEqualTo("2026 Sep?");
        assertThat(child.evidence().status()).isEqualTo("O");
        assertThat(event.updatedAt()).isEqualTo(IMPORTED);
    }

    /** ABD-45: an object with a photograph carries it and its credit; the others carry none. */
    @Test
    void anObjectWithAPhotographCarriesItsCredit() {
        jdbc.update("""
                INSERT INTO object_images (norad_id, file, thumb_url, thumb_width, thumb_height, author,
                                           licence, licence_url, description_url, updated_at)
                VALUES (100685, 'USA 667.jpg',
                        'https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/USA_667.jpg/500px-USA_667.jpg',
                        500, 375, 'U.S. Space Force', 'Public domain', NULL,
                        'https://commons.wikimedia.org/wiki/File:USA_667.jpg', now())""");
        try {
            Separations.Event event = separations.event("S100685").orElseThrow();

            assertThat(event.children().getFirst().image()).isEqualTo(new Separations.Image(
                    "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/USA_667.jpg/500px-USA_667.jpg",
                    500, 375, "U.S. Space Force", "Public domain", null,
                    "https://commons.wikimedia.org/wiki/File:USA_667.jpg"));
            assertThat(event.parent().image()).isNull();
        } finally {
            jdbc.update("DELETE FROM object_images");
        }
    }

    /**
     * ABD-13: FRG-10D1 left the FGN-TUG-S01 tug, which rode the Transporter-15 adapter stack
     * - an object of the auxiliary catalogue, unknown before auxcat.tsv was imported.
     */
    @Test
    void theLineageRunsIntoTheAuxiliaryCatalogue() {
        Separations.Event event = separations.event("S100400").orElseThrow();

        assertThat(event.parent().name()).isEqualTo("FGN-TUG-S01");
        assertThat(event.grandparent().name()).isEqualTo("Transporter-15");
        assertThat(event.grandparent().evidence().parent()).isEqualTo("A11696");
        assertThat(separations.event("A11846")).isEmpty();
        assertThat(separations.event("A11695")).isEmpty();
    }

    /** An auxiliary row often carries its spacecraft's NORAD number, never its photograph. */
    @Test
    void anAuxiliaryRowDoesNotTakeThePhotographOfItsNumber() {
        jdbc.update("UPDATE gcat_objects SET satcat = 100399 WHERE jcat = 'A11695'");
        jdbc.update("""
                INSERT INTO object_images (norad_id, file, thumb_url, thumb_width, thumb_height, author,
                                           licence, licence_url, description_url, updated_at)
                VALUES (100399, 'FGN-TUG-S01.jpg',
                        'https://upload.wikimedia.org/wikipedia/commons/thumb/f/fa/FGN.jpg/500px-FGN.jpg',
                        500, 375, 'Someone', 'CC BY 4.0', NULL,
                        'https://commons.wikimedia.org/wiki/File:FGN.jpg', now())""");
        try {
            Separations.Event event = separations.event("S100400").orElseThrow();

            assertThat(event.parent().image()).isNotNull();
            assertThat(event.grandparent().image()).isNull();
        } finally {
            jdbc.update("DELETE FROM object_images");
            jdbc.update("UPDATE gcat_objects SET satcat = NULL WHERE jcat = 'A11695'");
        }
    }

    /** ABD-15: the first import's events all share one first_seen_at; they go by separation date. */
    @Test
    void theFeedDatesTheFirstImportByItsSeparationDates() {
        List<Separations.FeedEntry> newest = separations.newest(3);

        assertThat(newest).extracting(Separations.FeedEntry::id).containsExactly("S100643", "S100685", "S100810");
        Separations.FeedEntry usa667 = newest.get(1);
        assertThat(usa667.publishedAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(usa667.kind()).isEqualTo(Kind.RELEASE);
        assertThat(usa667.parentName()).isEqualTo("USA 396");
        assertThat(usa667.parentId()).isEqualTo("S60322");
        assertThat(usa667.firstChildName()).isEqualTo("USA 667");
        assertThat(usa667.children()).isEqualTo(1);
        assertThat(usa667.date()).isEqualTo(new Separations.Date(
                "2026 Sep?", Instant.parse("2026-09-01T00:00:00Z"), "MONTH", true));
    }

    /** One entry per event, as many as the list has: a breakup's fragments are one item. */
    @Test
    void theFeedHasOneEntryPerEvent() {
        List<Separations.FeedEntry> newest = separations.newest(1000);

        assertThat(newest).extracting(Separations.FeedEntry::id).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        separations.latest(null, 1000).stream().map(Separations.Summary::id).toList());
        assertThat(newest).filteredOn(e -> e.id().equals("S100810")).singleElement()
                .satisfies(e -> assertThat(e.kind()).isEqualTo(Kind.FRAGMENTATION));
        // A parent missing from the catalogue: no name, its identifier for the title.
        assertThat(newest).filteredOn(e -> e.id().equals("S69731")).singleElement()
                .satisfies(e -> assertThat(e.parentName()).isNull())
                .satisfies(e -> assertThat(e.parentId()).isNotNull());
    }

    /**
     * The point of the feed: a separation recorded in 1999 that last night's import brought
     * in comes first, dated last night. A record GCAT marks as an error does not.
     */
    @Test
    void aSeparationImportedTonightComesFirstWhateverItsDate() {
        Instant tonight = Instant.parse("2026-10-08T03:12:00Z");
        String insert = """
                INSERT INTO gcat_objects (jcat, type, name, parent, parent_text, separation_text, separation_at,
                                          separation_precision, separation_uncertain, status, is_separation,
                                          first_seen_at, updated_at)
                VALUES (?, 'P', ?, 'S60322', 'S60322', '1999 Jan?', '1999-01-01T00:00:00Z',
                        'MONTH', true, ?, true, ?, ?)""";
        jdbc.update(insert, "S999001", "Old news", "O", Timestamp.from(tonight), Timestamp.from(tonight));
        jdbc.update(insert, "S999002", "A duplicate", "ERR", Timestamp.from(tonight.plusSeconds(60)),
                Timestamp.from(tonight.plusSeconds(60)));
        try {
            List<Separations.FeedEntry> newest = separations.newest(5);

            assertThat(newest.getFirst().id()).isEqualTo("S999001");
            assertThat(newest.getFirst().publishedAt()).isEqualTo(tonight);
            assertThat(newest.getFirst().date().at()).isEqualTo(Instant.parse("1999-01-01T00:00:00Z"));
            assertThat(newest.get(1).id()).isEqualTo("S100643");
            assertThat(newest).extracting(Separations.FeedEntry::id).doesNotContain("S999002");
        } finally {
            jdbc.update("DELETE FROM gcat_objects WHERE jcat IN ('S999001', 'S999002')");
        }
    }

    /** A member added to an old event later moves it back up, under the same identifier. */
    @Test
    void aLaterMemberDatesItsEventByTheNewestFirstSeen() {
        Instant later = Instant.parse("2026-10-05T03:00:00Z");
        String member = jdbc.queryForObject("""
                SELECT max(o.jcat) FROM gcat_objects o JOIN gcat_objects f ON f.jcat = 'S100810'
                WHERE o.parent = f.parent AND o.separation_text = f.separation_text""", String.class);
        jdbc.update("UPDATE gcat_objects SET first_seen_at = ? WHERE jcat = ?", Timestamp.from(later), member);
        try {
            Separations.FeedEntry first = separations.newest(1).getFirst();

            assertThat(first.id()).isEqualTo("S100810");
            assertThat(first.publishedAt()).isEqualTo(later);
        } finally {
            jdbc.update("UPDATE gcat_objects SET first_seen_at = ? WHERE jcat = ?",
                    Timestamp.from(IMPORTED), member);
        }
    }

    @Test
    void aParentMissingFromTheCatalogueIsNullNotAFailure() {
        Separations.Event event = separations.event("S69731").orElseThrow();

        assertThat(event.kind()).isEqualTo(Kind.FRAGMENTATION);
        assertThat(event.parent()).isNull();
        assertThat(event.grandparent()).isNull();
    }

    @Test
    void onlySeparationsHaveAPage() {
        assertThat(separations.event("S00001")).isEmpty();
        assertThat(separations.event("S03600")).isEmpty();
        assertThat(separations.event("S12345678")).isEmpty();
    }
}
