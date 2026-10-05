package space.nextpass.separations;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
        for (String file : List.of("satcat.tsv", "satcat100k.tsv", "families.tsv")) {
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
                // S03600 left Kosmos-249 on its launch day: a launch, not a separation.
                .hasSize(6);
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
