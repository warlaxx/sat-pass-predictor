package space.nextpass.images;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
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

/** Real PostgreSQL: the night replaces the table, and a bad night leaves it alone. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageImportPostgresTest {

    HikariDataSource source;
    JdbcTemplate jdbc;
    String schema;
    ImageRepository repository;
    TransactionTemplate transaction;

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
        repository = new ImageRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterAll
    void close() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        source.close();
    }

    @BeforeEach
    void empty() {
        jdbc.update("DELETE FROM object_images");
    }

    static ObjectImage image(int norad, String author) {
        return new ObjectImage(norad, norad + ".jpg",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/" + norad + ".jpg/500px-" + norad + ".jpg",
                500, 375, author, "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0",
                "https://commons.wikimedia.org/wiki/File:" + norad + ".jpg");
    }

    ImageImport night(String at, ImageSource source) {
        return new ImageImport(source, repository, transaction, Clock.fixed(Instant.parse(at), ZoneOffset.UTC));
    }

    @Test
    void eachNightReplacesTheImages() {
        ImageImport.Report first = night("2026-10-05T18:23:00Z", () -> List.of(image(20580, "NASA"), image(25544, "NASA"))).run();
        assertThat(first.status()).isEqualTo("imported");
        assertThat(first.images()).isEqualTo(2);

        ImageImport.Report second = night("2026-10-06T18:23:00Z",
                () -> List.of(image(25544, "ESA"), image(43013, "SpaceX"))).run();

        assertThat(second.images()).isEqualTo(2);
        assertThat(second.removed()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT norad_id FROM object_images ORDER BY norad_id", Integer.class))
                .containsExactly(25544, 43013);
        assertThat(jdbc.queryForObject("SELECT author FROM object_images WHERE norad_id = 25544", String.class))
                .isEqualTo("ESA");
    }

    @Test
    void anUnavailableSourceChangesNothing() {
        night("2026-10-05T18:23:00Z", () -> List.of(image(20580, "NASA"))).run();

        ImageImport.Report report = night("2026-10-06T18:23:00Z", () -> {
            throw new ImageSourceException("Commons is rate-limiting this client (429)");
        }).run();

        assertThat(report.status()).isEqualTo("unavailable");
        assertThat(report.detail()).contains("429");
        assertThat(report.images()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    /** A list less than half as long as the stored one is a truncated answer, not a purge. */
    @Test
    void aSuspiciouslyShortListIsNotApplied() {
        night("2026-10-05T18:23:00Z", () -> IntStream.rangeClosed(1, 10).mapToObj(n -> image(n, "NASA")).toList()).run();

        ImageImport.Report report = night("2026-10-06T18:23:00Z", () -> List.of(image(1, "NASA"))).run();

        assertThat(report.status()).isEqualTo("suspicious");
        assertThat(repository.count()).isEqualTo(10);
    }
}
