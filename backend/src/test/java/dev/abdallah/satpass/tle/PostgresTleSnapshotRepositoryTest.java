package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real PostgreSQL: the upsert's guard clause is the whole point of this class, and
 * {@code ON CONFLICT ... WHERE} is not something to 'verify' against H2.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresTleSnapshotRepositoryTest {

    private static final int ISS = 25544;
    private static final Instant EPOCH = Instant.parse("2021-02-04T03:28:36.316Z");
    private static final Instant FETCHED = Instant.parse("2021-02-04T08:00:00Z");

    HikariDataSource source;
    JdbcTemplate jdbc;
    String schema;
    PostgresTleSnapshotRepository repository;

    @BeforeAll
    void open() {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "satpass"));
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
        repository = new PostgresTleSnapshotRepository(new JdbcTemplate(source));
    }

    @AfterAll
    void close() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        source.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM tle_snapshots");
    }

    private static TleSnapshot snapshot(Instant fetchedAt) {
        return new TleSnapshot(ISS, TleFixtures.issName(), TleFixtures.issLine1(),
                TleFixtures.issLine2(), EPOCH, fetchedAt, "celestrak");
    }

    @Test
    void whatIsStoredComesBackIdentical() {
        TleSnapshot stored = snapshot(FETCHED);

        repository.save(stored);

        // The two lines above all: a column that trimmed or padded them would produce a
        // row that no longer parses as a TLE, and the 69-character check would only
        // catch it on the way back out.
        assertThat(repository.find(ISS)).contains(stored);
    }

    @Test
    void aSecondFetchReplacesTheFirst() {
        repository.save(snapshot(FETCHED));
        TleSnapshot newer = snapshot(FETCHED.plus(Duration.ofHours(3)));

        repository.save(newer);

        assertThat(repository.find(ISS)).contains(newer);
    }

    @Test
    void anOlderFetchNeverOverwritesANewerOne() {
        TleSnapshot newer = snapshot(FETCHED.plus(Duration.ofHours(3)));
        repository.save(newer);

        // The other instance was slower to commit than it was to fetch. Accepting its
        // write would move the stored age backwards, for no reason a reader could explain.
        repository.save(snapshot(FETCHED));

        assertThat(repository.find(ISS)).contains(newer);
    }

    @Test
    void forgettingASatelliteLeavesNothingBehind() {
        repository.save(snapshot(FETCHED));

        repository.remove(ISS);

        assertThat(repository.find(ISS)).isEmpty();
    }

    @Test
    void anUnknownSatelliteIsAnEmptyAnswerAndNotAFailure() {
        assertThat(repository.find(99999)).isEmpty();
        repository.remove(99999);
    }

    @Test
    void aCorruptRowIsIgnoredRatherThanPropagated() {
        repository.save(snapshot(FETCHED));
        // 69 characters, so the table's own CHECK is satisfied, but not a line 1: exactly
        // the kind of row a future migration or a manual fix could leave behind.
        jdbc.update("UPDATE tle_snapshots SET line1 = ? WHERE norad_id = ?",
                "9".repeat(69), ISS);

        assertThat(repository.find(ISS)).isEmpty();
    }
}
