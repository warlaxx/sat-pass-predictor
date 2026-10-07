package space.nextpass.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static space.nextpass.alerts.AlertFixtures.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.jdbc.core.JdbcTemplate;
import space.nextpass.TleFixtures;
import space.nextpass.domain.ObserverLocation;
import space.nextpass.domain.SatellitePass;
import space.nextpass.domain.TleSnapshot;
import space.nextpass.passes.PassPrediction;
import space.nextpass.passes.PassQueryService;
import space.nextpass.tle.MutableClock;
import space.nextpass.tle.TleNotFoundException;
import space.nextpass.tle.TleStore;
import space.nextpass.weather.CloudCoverService;
import space.nextpass.weather.CloudForecast;
import space.nextpass.weather.WeatherUnavailableException;

/** The sign-up and the hourly round against a real database, with the world around them faked. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertServicePostgresTest {

    /** 13:30 UTC: 15:30 in Paris, just past the hour a subscriber's day is looked at. */
    static final Instant AFTERNOON = Instant.parse("2026-10-08T13:30:00Z");
    static final TleSnapshot ISS = new TleSnapshot(25544, "ISS (ZARYA)", TleFixtures.issLine1(),
            TleFixtures.issLine2(), Instant.parse("2026-10-08T04:00:00Z"), AFTERNOON, "celestrak");

    HikariDataSource source;
    JdbcTemplate jdbc;
    String schema;
    AlertRepository repository;

    MutableClock clock;
    List<Email> outbox;
    TleStore tles;
    PassQueryService passes;
    CloudCoverService clouds;
    AlertService service;

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
        repository = new AlertRepository(jdbc);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE pass_alerts, alert_email_counts");
        clock = new MutableClock(AFTERNOON);
        outbox = new ArrayList<>();
        tles = mock(TleStore.class);
        passes = mock(PassQueryService.class);
        clouds = mock(CloudCoverService.class);
        when(tles.get(25544)).thenReturn(ISS);
        service(5, 95, 30);
    }

    void service(int maxPerEmail, int dailyCap, int confirmationCap) {
        service = new AlertService(repository, outbox::add,
                new AlertEmails("https://www.nextpass.space", "https://www.nextpass.space"), tles, passes, clouds, clock,
                new AlertService.Settings(15, maxPerEmail, dailyCap, confirmationCap, Duration.ZERO));
    }

    @AfterAll
    void close() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        source.close();
    }

    static AlertRequest request(String email, double lat) {
        return new AlertRequest(email, 25544, lat, 4.8320, 30, 25, null, "Europe/Paris", "en");
    }

    String tokenOf(String email) {
        return jdbc.queryForObject("SELECT token FROM pass_alerts WHERE email = ? ORDER BY id LIMIT 1", String.class, email);
    }

    void tonight(SatellitePass... tonight) {
        when(passes.findPasses(eq(25544), any(ObserverLocation.class), any(Duration.class), anyDouble()))
                .thenAnswer(call -> new PassPrediction(ISS, call.getArgument(1), 10.0, clock.instant(), List.of(tonight)));
    }

    void sky(int cloudPercent) {
        CloudForecast forecast = clouds(cloudPercent);
        // doReturn, not when(): the mock may have been told to throw, and when() would call it.
        doReturn(new CloudCoverService.Result(forecast, clock.instant().plusSeconds(3600)))
                .when(clouds).forecast(anyDouble(), anyDouble());
    }

    static SatellitePass goodPass() {
        return pass(Instant.parse("2026-10-08T19:14:00Z"), seen(12), seen(64), seen(20));
    }

    String subscribeAndConfirm(String email) {
        service.subscribe(request(email, 45.7578));
        String token = tokenOf(email);
        assertThat(service.confirm(token)).isPresent();
        outbox.clear();
        return token;
    }

    // --- Sign-up -------------------------------------------------------------------

    @Test
    void aSignUpStoresARoundedPlaceAndSendsOneConfirmation() {
        assertThat(service.subscribe(request("Ada@Example.org", 45.7578))).isEqualTo(AlertService.SignupOutcome.PENDING);

        assertThat(outbox).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo("ada@example.org");
            assertThat(email.text()).contains("/alerts/confirm?token=" + tokenOf("ada@example.org"));
        });
        assertThat(jdbc.queryForObject("SELECT latitude_deg FROM pass_alerts", Double.class)).isEqualTo(45.76);
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NULL FROM pass_alerts", Boolean.class)).isTrue();
    }

    @Test
    void theSameAddressAgainWithinTenMinutesGetsNoSecondEmail() {
        service.subscribe(request("ada@example.org", 45.7578));
        service.subscribe(request("ada@example.org", 45.7578));
        service.subscribe(request("ada@example.org", 48.85)); // another place, same address

        assertThat(outbox).hasSize(1);

        clock.advance(Duration.ofMinutes(11));
        service.subscribe(request("ada@example.org", 45.7578));
        assertThat(outbox).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pass_alerts", Long.class)).isEqualTo(2);
    }

    @Test
    void aConfirmedSubscriptionCannotBeChangedOrReannouncedByAStranger() {
        subscribeAndConfirm("ada@example.org");
        clock.advance(Duration.ofHours(1));

        AlertService.SignupOutcome outcome = service.subscribe(
                new AlertRequest("ada@example.org", 25544, 45.7578, 4.832, 80, 100, null, "Europe/Paris", "en"));

        assertThat(outcome).isEqualTo(AlertService.SignupOutcome.PENDING);
        assertThat(outbox).isEmpty();
        assertThat(jdbc.queryForObject("SELECT min_elevation_deg FROM pass_alerts", Integer.class)).isEqualTo(30);
    }

    @Test
    void anAddressHoldsAtMostItsShare() {
        service(2, 95, 30);
        service.subscribe(request("ada@example.org", 10));
        service.subscribe(request("ada@example.org", 20));

        assertThat(service.subscribe(request("ada@example.org", 30))).isEqualTo(AlertService.SignupOutcome.TOO_MANY);
    }

    @Test
    void confirmationsStopAtTheirShareOfTheDay() {
        service(5, 95, 2);
        service.subscribe(request("a@example.org", 10));
        service.subscribe(request("b@example.org", 10));

        assertThat(service.subscribe(request("c@example.org", 10))).isEqualTo(AlertService.SignupOutcome.BUSY);
        assertThat(outbox).hasSize(2);
    }

    @Test
    void anUnknownSatelliteIsRefusedBeforeAnythingIsStored() {
        when(tles.get(99999)).thenThrow(new TleNotFoundException(99999));

        assertThatThrownBy(() -> service.subscribe(
                new AlertRequest("a@b.co", 99999, 45.0, 5.0, null, null, null, "Europe/Paris", "en")))
                .isInstanceOf(TleNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pass_alerts", Long.class)).isZero();
    }

    @Test
    void confirmingTwiceIsHarmlessAndAWrongTokenFindsNothing() {
        service.subscribe(request("ada@example.org", 45.7578));
        String token = tokenOf("ada@example.org");

        assertThat(service.confirm(token)).isPresent();
        assertThat(service.confirm(token)).isPresent();
        assertThat(service.confirm("x".repeat(43))).isEmpty();
        assertThat(service.confirm("'; DROP TABLE pass_alerts; --")).isEmpty();
    }

    @Test
    void unsubscribingDeletesTheRow() {
        String token = subscribeAndConfirm("ada@example.org");

        assertThat(service.unsubscribe(token)).isTrue();
        assertThat(service.unsubscribe(token)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pass_alerts", Long.class)).isZero();
    }

    // --- The hourly round ----------------------------------------------------------

    @Test
    void aGoodPassUnderAClearSkySendsOneReminderADay() {
        subscribeAndConfirm("ada@example.org");
        tonight(goodPass());
        sky(10);

        AlertService.Report first = service.dispatch();
        clock.advance(Duration.ofHours(1));
        AlertService.Report second = service.dispatch();

        assertThat(first.sent()).isEqualTo(1);
        assertThat(outbox).singleElement().satisfies(email ->
                assertThat(email.subject()).isEqualTo("ISS (ZARYA): visible at 21:14, clear sky"));
        assertThat(second.due()).isZero();
        assertThat(jdbc.queryForObject("SELECT last_sent_on FROM pass_alerts", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 10, 8));

        clock.advance(Duration.ofDays(1));
        assertThat(service.dispatch().due()).isEqualTo(1);
    }

    @Test
    void nobodyIsLookedAtBeforeTheirLocalAfternoon() {
        subscribeAndConfirm("ada@example.org");
        tonight(goodPass());
        sky(10);
        clock = new MutableClock(Instant.parse("2026-10-08T12:30:00Z")); // 14:30 in Paris
        service(5, 95, 30);

        AlertService.Report report = service.dispatch();

        assertThat(report.subscriptions()).isEqualTo(1);
        assertThat(report.due()).isZero();
        verify(passes, never()).findPasses(anyInt(), any(), any(), anyDouble());
    }

    @Test
    void aCloudyNightIsCheckedAndSilent() {
        subscribeAndConfirm("ada@example.org");
        tonight(goodPass());
        sky(80);

        AlertService.Report report = service.dispatch();

        assertThat(report.nothingWorthIt()).isEqualTo(1);
        assertThat(outbox).isEmpty();
        assertThat(jdbc.queryForObject("SELECT last_checked_on FROM pass_alerts", LocalDate.class))
                .isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void noVisiblePassSparesTheWeatherRequest() {
        subscribeAndConfirm("ada@example.org");
        tonight(pass(Instant.parse("2026-10-08T19:14:00Z"), dark(12), dark(64), dark(20)));

        assertThat(service.dispatch().nothingWorthIt()).isEqualTo(1);
        verify(clouds, never()).forecast(anyDouble(), anyDouble());
    }

    @Test
    void noForecastLeavesTheDayOpenForTheNextRound() {
        subscribeAndConfirm("ada@example.org");
        tonight(goodPass());
        when(clouds.forecast(anyDouble(), anyDouble())).thenThrow(new WeatherUnavailableException("budget"));

        assertThat(service.dispatch().retryLater()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_checked_on FROM pass_alerts", LocalDate.class)).isNull();

        sky(5);
        clock.advance(Duration.ofHours(1));
        assertThat(service.dispatch().sent()).isEqualTo(1);
    }

    @Test
    void whenTheDayIsSpentTheReminderWaitsAndIsNotMarked() {
        subscribeAndConfirm("ada@example.org"); // one confirmation spent
        tonight(goodPass());
        sky(10);
        service(5, 1, 30);

        AlertService.Report report = service.dispatch();

        assertThat(report.budgetSpent()).isEqualTo(1);
        assertThat(outbox).isEmpty();
        assertThat(jdbc.queryForObject("SELECT last_sent_on FROM pass_alerts", LocalDate.class)).isNull();
    }

    @Test
    void unconfirmedSignUpsAreForgottenAfterFortyEightHours() {
        tonight();
        service.subscribe(request("ada@example.org", 45.7578));
        subscribeAndConfirm("bob@example.org");

        clock.advance(Duration.ofHours(47));
        assertThat(service.dispatch().purged()).isZero();
        clock.advance(Duration.ofHours(2));
        assertThat(service.dispatch().purged()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT email FROM pass_alerts", String.class)).containsExactly("bob@example.org");
    }
}
