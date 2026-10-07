package space.nextpass.gcat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import space.nextpass.tle.MutableClock;

/**
 * Real PostgreSQL: the upsert's {@code IS DISTINCT FROM} guard and the one-transaction-
 * per-file rule are what this class is about, and neither can be checked against a mock.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GcatImporterPostgresTest {

    private static final Instant FIRST_NIGHT = Instant.parse("2026-10-02T18:23:00Z");

    HikariDataSource source;
    JdbcTemplate jdbc;
    String schema;
    FixtureSource gcat;
    MutableClock clock;
    GcatImporter importer;

    /** Serves the fixture files, or a replacement body, with an ETag the test controls. */
    static class FixtureSource implements GcatSource {
        final Map<String, String> bodies = new HashMap<>();
        final Map<String, String> etags = new HashMap<>();
        String failAfterReading;

        FixtureSource() {
            for (String file : List.of("satcat.tsv", "satcat100k.tsv", "auxcat.tsv")) {
                try (var in = getClass().getResourceAsStream("/gcat/" + file)) {
                    bodies.put(file, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                etags.put(file, "\"v1\"");
            }
        }

        @Override
        public Optional<Validators> fetch(String file, Validators previous, Consumer<BufferedReader> body) {
            if (etags.get(file).equals(previous.etag())) {
                return Optional.empty();
            }
            body.accept(new BufferedReader(new StringReader(bodies.get(file))));
            if (file.equals(failAfterReading)) {
                throw new GcatImportException("connection reset while reading " + file);
            }
            return Optional.of(new Validators(etags.get(file), "Fri, 02 Oct 2026 13:53:24 GMT"));
        }
    }

    @BeforeAll
    void open() {
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
    }

    @AfterAll
    void close() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        source.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM gcat_objects");
        jdbc.update("DELETE FROM gcat_files");
        gcat = new FixtureSource();
        clock = new MutableClock(FIRST_NIGHT);
        importer = new GcatImporter(gcat, List.of("satcat.tsv", "satcat100k.tsv"),
                new GcatRepository(new JdbcTemplate(source)),
                new TransactionTemplate(new DataSourceTransactionManager(source)), clock);
    }

    /** ABD-13: auxcat.tsv comes in for the lineage; none of its rows becomes a separation. */
    @Test
    void theAuxiliaryCatalogueIsImportedButNeverListed() {
        importer = new GcatImporter(gcat, List.of("satcat.tsv", "satcat100k.tsv", "auxcat.tsv"),
                new GcatRepository(new JdbcTemplate(source)),
                new TransactionTemplate(new DataSourceTransactionManager(source)), clock);

        GcatImporter.Report report = importer.run();

        assertThat(report.files()).last().isEqualTo(new GcatImporter.FileReport("auxcat.tsv", "imported", 3, 0));
        assertThat(report.newObjects()).isEqualTo(13);
        assertThat(report.newSeparations()).isEqualTo(6);
        // A11846, a Dragon trunk dropped a month after launch: the rule would say yes.
        assertThat(jdbc.queryForMap("SELECT parent, is_separation FROM gcat_objects WHERE jcat = 'A11846'"))
                .containsEntry("parent", "S69103")
                .containsEntry("is_separation", false);
    }

    @Test
    void theFirstNightImportsBothFiles() {
        GcatImporter.Report report = importer.run();

        assertThat(report.status()).isEqualTo("imported");
        assertThat(report.files()).containsExactly(
                new GcatImporter.FileReport("satcat.tsv", "imported", 7, 0),
                new GcatImporter.FileReport("satcat100k.tsv", "imported", 3, 0));
        assertThat(report.rowsRead()).isEqualTo(10);
        assertThat(report.newObjects()).isEqualTo(10);
        assertThat(report.updatedObjects()).isZero();
        assertThat(report.newSeparations()).isEqualTo(6);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM gcat_objects WHERE jcat = 'S69731'");
        assertThat(row.get("separation_text")).isEqualTo("2026 May?");
        assertThat(row.get("separation_precision")).isEqualTo("MONTH");
        assertThat(row.get("separation_uncertain")).isEqualTo(true);
        assertThat(row.get("parent")).isEqualTo("S40340");
        assertThat(row.get("is_separation")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT etag FROM gcat_files WHERE name = 'satcat100k.tsv'", String.class))
                .isEqualTo("\"v1\"");
    }

    @Test
    void anUnchangedFileIsNotDownloadedTwice() {
        importer.run();
        clock.advance(Duration.ofDays(1));

        GcatImporter.Report report = importer.run();

        assertThat(report.status()).isEqualTo("unchanged");
        assertThat(report.rowsRead()).isZero();
        assertThat(report.newObjects()).isZero();
    }

    @Test
    void aRepublishedFileWritesOnlyWhatChanged() {
        importer.run();
        clock.advance(Duration.ofDays(1));
        gcat.etags.put("satcat.tsv", "\"v2\"");
        gcat.bodies.computeIfPresent("satcat.tsv", (file, body) ->
                body.replace("Shenzhou 22 Guidao Cang", "Shenzhou-22 Guidao Cang"));

        GcatImporter.Report report = importer.run();

        assertThat(report.files()).extracting(GcatImporter.FileReport::status)
                .containsExactly("imported", "unchanged");
        assertThat(report.rowsRead()).isEqualTo(7);
        assertThat(report.newObjects()).isZero();
        assertThat(report.updatedObjects()).isEqualTo(1);
        // first_seen_at is when NextPass first saw the object, whatever changed since.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, first_seen_at, updated_at FROM gcat_objects WHERE jcat = 'S69328'");
        assertThat(row.get("name")).isEqualTo("Shenzhou-22 Guidao Cang");
        assertThat(((java.sql.Timestamp) row.get("first_seen_at")).toInstant()).isEqualTo(FIRST_NIGHT);
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant()).isEqualTo(FIRST_NIGHT.plus(Duration.ofDays(1)));
    }

    @Test
    void aNewRowCountsAsNewAndOnlyOnce() {
        importer.run();
        clock.advance(Duration.ofDays(1));
        gcat.etags.put("satcat100k.tsv", "\"v2\"");
        gcat.bodies.computeIfPresent("satcat100k.tsv", (file, body) -> body
                + body.lines().filter(line -> line.startsWith("S100810")).findFirst().orElseThrow()
                        .replace("S100810", "S100822").replace("100810", "100822") + "\n");

        GcatImporter.Report report = importer.run();

        assertThat(report.newObjects()).isEqualTo(1);
        assertThat(report.newSeparations()).isEqualTo(1);
        assertThat(report.updatedObjects()).isZero();
    }

    @Test
    void aDownloadCutHalfwayLeavesThePreviousNightIntact() {
        importer.run();
        clock.advance(Duration.ofDays(1));
        gcat.etags.put("satcat.tsv", "\"v2\"");
        gcat.bodies.computeIfPresent("satcat.tsv", (file, body) -> body.replace("EVA screwdriver", "EVA wrench"));
        gcat.failAfterReading = "satcat.tsv";

        assertThatThrownBy(importer::run).isInstanceOf(GcatImportException.class);

        assertThat(jdbc.queryForObject("SELECT name FROM gcat_objects WHERE jcat = 'S16013'", String.class))
                .isEqualTo("EVA screwdriver");
        // The old ETag stays, so the next night downloads the file again.
        assertThat(jdbc.queryForObject("SELECT etag FROM gcat_files WHERE name = 'satcat.tsv'", String.class))
                .isEqualTo("\"v1\"");
    }
}
