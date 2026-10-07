package space.nextpass.delays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import space.nextpass.tle.MutableClock;
import space.nextpass.tle.TleUnavailableException;

/**
 * Real PostgreSQL: the delays are computed by the {@code catalogue_delays} view, and the
 * exclusion of the first import is a subquery a mock could not check.
 *
 * <p>The story: GCAT's first import on 2 October marks two objects "first seen"; the next
 * night it lists a separation and a new object; Space-Track had catalogued both earlier,
 * plus one GCAT does not list yet.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DelayMeasurementPostgresTest {

    private static final Instant FIRST_IMPORT = Instant.parse("2026-10-02T18:23:00Z");
    private static final Instant SECOND_NIGHT = Instant.parse("2026-10-03T18:23:00Z");

    HikariDataSource source;
    JdbcTemplate jdbc;
    String schema;
    MutableClock clock;
    FakeSource spaceTrack;

    static class FakeSource implements SpaceTrackDebutSource {
        List<SpaceTrackDebut> debuts = new ArrayList<>();
        RuntimeException failure;

        @Override
        public List<SpaceTrackDebut> recent() {
            if (failure != null) {
                throw failure;
            }
            return debuts;
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
        jdbc.update("DELETE FROM spacetrack_debuts");
        jdbc.update("DELETE FROM gcat_objects");
        clock = new MutableClock(SECOND_NIGHT.plusSeconds(60));
        spaceTrack = new FakeSource();

        // The first import: everything it holds is "first seen" at once.
        gcatObject("S69998", 69998, "OBJECT Z", true, "2026 Jul 11 0402:25",
                "2026-07-11T04:02:25Z", "SECOND", FIRST_IMPORT);
        gcatObject("S69731", 69731, "SJ-17 DEB", true, "2026 May?",
                "2026-05-01T00:00:00Z", "MONTH", FIRST_IMPORT);
        // The second night: a separation known to the minute, and an ordinary new object.
        gcatObject("S100961", 100961, "USA 700", true, "2026 Oct  2 0402",
                "2026-10-02T04:02:00Z", "MINUTE", SECOND_NIGHT);
        gcatObject("S101000", 101000, "STARLINK-40000", false, null, null, null, SECOND_NIGHT);

        spaceTrack.debuts = List.of(
                debut(69998, "2026-07-12T03:00:00Z"),
                debut(100961, "2026-10-02T12:00:00Z"),
                debut(101000, "2026-10-03T06:23:00Z"),
                // Catalogued by Space-Track, not listed by GCAT yet.
                debut(101500, "2026-10-03T10:00:00Z"));
    }

    private DelayMeasurement measurement(SpaceTrackDebutSource debuts) {
        return new DelayMeasurement(debuts, new DelayRepository(jdbc), clock);
    }

    @Test
    void measuresGcatAgainstSpaceTrackAndTheSeparation() {
        DelayMeasurement.Report report = measurement(spaceTrack).run(SECOND_NIGHT);

        assertThat(report.status()).isEqualTo("measured");
        assertThat(report.debutsFetched()).isEqualTo(4);
        assertThat(report.newDebuts()).isEqualTo(4);

        // USA 700: Space-Track 12:00 on 2 Oct, GCAT's night of 3 Oct (18:23), separation 04:02.
        assertThat(report.separations().objects()).isEqualTo(1);
        assertThat(report.separations().gcatAfterSpaceTrack()).isEqualTo(new DelayMeasurement.Stat(30.4, 30.4));
        assertThat(report.separations().spaceTrackAfterSeparation()).isEqualTo(new DelayMeasurement.Stat(8.0, 8.0));
        assertThat(report.separations().gcatAfterSeparation()).isEqualTo(new DelayMeasurement.Stat(38.4, 38.4));

        // With the Starlink, 12 h behind Space-Track: median 21.2, 90th percentile 28.5.
        assertThat(report.allObjects().objects()).isEqualTo(2);
        assertThat(report.allObjects().gcatAfterSpaceTrack()).isEqualTo(new DelayMeasurement.Stat(21.2, 28.5));
        assertThat(report.awaitingGcat()).isEqualTo(1);

        assertThat(report.newlyMeasured()).extracting(DelayMeasurement.Delay::noradId)
                .containsExactly(100961, 101000);
        DelayMeasurement.Delay separation = report.newlyMeasured().getFirst();
        assertThat(separation.separation()).isTrue();
        assertThat(separation.separationText()).isEqualTo("2026 Oct  2 0402");
        assertThat(separation.gcatFirstSeenAt()).isEqualTo(SECOND_NIGHT);
    }

    /** The first import's 70 000 "first seen" say nothing about GCAT's speed. */
    @Test
    void ignoresWhatTheFirstImportFound() {
        measurement(spaceTrack).run(SECOND_NIGHT);

        assertThat(jdbc.queryForList("SELECT norad_id FROM catalogue_delays", Integer.class))
                .containsExactlyInAnyOrder(100961, 101000);
    }

    /** A month-precise separation would turn "May?" into the first of May: not measured. */
    @Test
    void measuresFromASeparationOnlyWhenItsDayIsKnown() {
        gcatObject("S101100", 101100, "DEB", true, "2026 Sep?", "2026-09-01T00:00:00Z", "MONTH", SECOND_NIGHT);
        spaceTrack.debuts = List.of(debut(101100, "2026-10-01T00:00:00Z"));

        DelayMeasurement.Delay delay = measurement(spaceTrack).run(SECOND_NIGHT).newlyMeasured().getFirst();

        assertThat(delay.gcatAfterSpaceTrackHours()).isEqualTo(66.4);
        assertThat(delay.spaceTrackAfterSeparationHours()).isNull();
        assertThat(delay.gcatAfterSeparationHours()).isNull();
    }

    /** The window overlaps from night to night: a debut seen again is not new, nor re-dated. */
    @Test
    void aSecondNightKeepsTheFirstSighting() {
        measurement(spaceTrack).run(SECOND_NIGHT);
        clock.advance(Duration.ofDays(1));

        DelayMeasurement.Report report = measurement(spaceTrack).run(SECOND_NIGHT.plus(Duration.ofDays(1)));

        assertThat(report.debutsFetched()).isEqualTo(4);
        assertThat(report.newDebuts()).isZero();
        assertThat(report.newlyMeasured()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT first_fetched_at FROM spacetrack_debuts WHERE norad_id = 100961",
                Timestamp.class).toInstant()).isEqualTo(SECOND_NIGHT.plusSeconds(60));
    }

    /** GCAT listing it first is a measurement too: the debut arrives later and closes it. */
    @Test
    void anObjectGcatListsFirstIsMeasuredWhenSpaceTrackCatalogues() {
        spaceTrack.debuts = List.of();
        measurement(spaceTrack).run(SECOND_NIGHT);

        clock.advance(Duration.ofDays(1));
        spaceTrack.debuts = List.of(debut(101000, "2026-10-04T08:00:00Z"));
        DelayMeasurement.Report report = measurement(spaceTrack).run(SECOND_NIGHT.plus(Duration.ofDays(1)));

        assertThat(report.newlyMeasured()).singleElement()
                .satisfies(delay -> assertThat(delay.gcatAfterSpaceTrackHours()).isEqualTo(-13.6));
    }

    /** Two GCAT rows with one NORAD number are one object, dated by GCAT's first sighting. */
    @Test
    void aDuplicateNoradNumberCountsOnce() {
        gcatObject("S101000X", 101000, "STARLINK-40000 DUP", false, null, null, null,
                SECOND_NIGHT.plus(Duration.ofDays(1)));

        DelayMeasurement.Report report = measurement(spaceTrack).run(SECOND_NIGHT);

        assertThat(report.allObjects().objects()).isEqualTo(2);
        assertThat(report.newlyMeasured()).filteredOn(delay -> delay.noradId() == 101000).singleElement()
                .satisfies(delay -> assertThat(delay.jcat()).isEqualTo("S101000"));
    }

    /** And a duplicate GCAT already had at its first import keeps the object out. */
    @Test
    void aDuplicateOfAnObjectTheFirstImportHadIsNotNew() {
        gcatObject("S69998X", 69998, "OBJECT Z DUP", true, null, null, null, SECOND_NIGHT);

        measurement(spaceTrack).run(SECOND_NIGHT);

        assertThat(jdbc.queryForList("SELECT norad_id FROM catalogue_delays", Integer.class))
                .containsExactlyInAnyOrder(100961, 101000);
    }

    /**
     * ABD-13: an auxiliary-catalogue row carrying an object's number, first seen the same
     * night, neither speaks for it ('A' sorts before 'S') nor counts as GCAT listing it.
     */
    @Test
    void anAuxiliaryRowDoesNotSpeakForTheObject() {
        gcatObject("A11999", 100961, "USA 700 ADAPTER", false, null, null, null, SECOND_NIGHT);
        gcatObject("A12000", 101500, "STAGE OF 101500", false, null, null, null, SECOND_NIGHT);

        DelayMeasurement.Report report = measurement(spaceTrack).run(SECOND_NIGHT);

        assertThat(report.separations().objects()).isEqualTo(1);
        assertThat(report.allObjects().objects()).isEqualTo(2);
        assertThat(report.awaitingGcat()).isEqualTo(1);
        assertThat(report.newlyMeasured()).filteredOn(delay -> delay.noradId() == 100961).singleElement()
                .satisfies(delay -> assertThat(delay.jcat()).isEqualTo("S100961"));
    }

    /** The database failing is not Space-Track failing: it is thrown, for the caller to report. */
    @Test
    void aDatabaseFailureIsNotReportedAsSpaceTrackUnavailable() {
        jdbc.execute("ALTER TABLE spacetrack_debuts RENAME TO spacetrack_debuts_gone");
        try {
            assertThatThrownBy(() -> measurement(spaceTrack).run(SECOND_NIGHT))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("ALTER TABLE spacetrack_debuts_gone RENAME TO spacetrack_debuts");
        }
    }

    /** Space-Track down is a night without debuts, not a failed import. */
    @Test
    void spaceTrackUnavailableStillReportsWhatIsKnown() {
        measurement(spaceTrack).run(SECOND_NIGHT);
        spaceTrack.failure = new TleUnavailableException("https://www.space-track.org unreachable for the catalogue debuts");

        DelayMeasurement.Report report = measurement(spaceTrack).run(SECOND_NIGHT);

        assertThat(report.status()).isEqualTo("unavailable");
        assertThat(report.detail()).contains("unreachable");
        assertThat(report.debutsFetched()).isZero();
        assertThat(report.allObjects().objects()).isEqualTo(2);
    }

    @Test
    void withoutCredentialsItSaysSo() {
        DelayMeasurement.Report report = measurement(null).run(SECOND_NIGHT);

        assertThat(report.status()).isEqualTo("not-configured");
        assertThat(report.allObjects().objects()).isZero();
        assertThat(report.allObjects().gcatAfterSpaceTrack()).isEqualTo(new DelayMeasurement.Stat(null, null));
    }

    private void gcatObject(String jcat, int satcat, String name, boolean separation, String separationText,
                            String separationAt, String precision, Instant firstSeen) {
        jdbc.update("""
                INSERT INTO gcat_objects (jcat, satcat, name, is_separation, separation_text, separation_at,
                    separation_precision, first_seen_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                jcat, satcat, name, separation, separationText,
                separationAt == null ? null : Timestamp.from(Instant.parse(separationAt)),
                precision, Timestamp.from(firstSeen), Timestamp.from(firstSeen));
    }

    private static SpaceTrackDebut debut(int noradId, String at) {
        return new SpaceTrackDebut(noradId, Instant.parse(at), null, "OBJECT " + noradId);
    }
}
