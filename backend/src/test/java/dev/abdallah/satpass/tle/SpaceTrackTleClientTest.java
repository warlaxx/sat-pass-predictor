package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import dev.abdallah.satpass.OrekitTest;
import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The authenticated source, against what Space-Track really answers.
 *
 * <p>No network and no account: {@link MockRestServiceServer} intercepts the exchanges,
 * which is the only way this class can be tested at all — running it for real would need
 * credentials in CI and would spend a rate-limited budget on assertions.
 *
 * <p>The Spring context is loaded only for {@link DataContext}: turning a TLE's epoch into
 * an {@link Instant} goes through Orekit's time scales.
 */
@OrekitTest
class SpaceTrackTleClientTest {

    private static final String BASE_URL = "https://spacetrack.test";
    private static final String LOGIN = BASE_URL + "/ajaxauth/login";
    private static final String QUERY =
            BASE_URL + "/basicspacedata/query/class/gp/NORAD_CAT_ID/25544/limit/1/format/3le";
    private static final Instant FETCHED_AT = Instant.parse("2021-02-04T08:00:00Z");
    private static final int ISS = 25544;

    @Autowired
    DataContext dataContext;

    private MockRestServiceServer server;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(FETCHED_AT);
    }

    private SpaceTrackTleClient clientWithBudget(int perMinute, int perHour) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new SpaceTrackTleClient(BASE_URL, builder.build(), dataContext,
                Clock.fixed(FETCHED_AT, ZoneOffset.UTC),
                new RequestBudget(perMinute, perHour, clock),
                "pilot@example.test", "secret");
    }

    private SpaceTrackTleClient client() {
        return clientWithBudget(60, 600);
    }

    /** What {@code format/3le} returns: the name line carries a leading "0 ". */
    private static String threeLineElements() {
        return "0 " + TleFixtures.issName() + "\r\n"
                + TleFixtures.issLine1() + "\r\n"
                + TleFixtures.issLine2() + "\r\n";
    }

    private void expectLogin(String body) {
        server.expect(requestTo(LOGIN))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectQuery() {
        server.expect(requestTo(QUERY))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(threeLineElements(), MediaType.TEXT_PLAIN));
    }

    @Test
    void logsInThenReadsTheThreeLineElements() {
        SpaceTrackTleClient client = client();
        expectLogin("");
        expectQuery();

        TleSnapshot snapshot = client.fetch(ISS);

        server.verify();
        assertThat(snapshot.noradId()).isEqualTo(ISS);
        assertThat(snapshot.line1()).isEqualTo(TleFixtures.issLine1());
        assertThat(snapshot.line2()).isEqualTo(TleFixtures.issLine2());
        assertThat(snapshot.source()).isEqualTo("space-track");
    }

    /**
     * One satellite, one name, whichever source answered. The {@code 0 } belongs to the
     * 3LE format, not to the object.
     */
    @Test
    void dropsTheThreeLineFormatPrefixFromTheName() {
        SpaceTrackTleClient client = client();
        expectLogin("");
        expectQuery();

        assertThat(client.fetch(ISS).name()).isEqualTo(TleFixtures.issName());
    }

    /**
     * Space-Track's API rules ask for the session cookie to be reused rather than a login
     * per query. Two fetches, one login.
     */
    @Test
    void keepsTheSessionForTheNextFetch() {
        SpaceTrackTleClient client = client();
        expectLogin("");
        expectQuery();
        expectQuery();

        client.fetch(ISS);
        client.fetch(ISS);

        server.verify();
    }

    @Test
    void logsInAgainWhenTheSessionIsRefused() {
        SpaceTrackTleClient client = client();
        expectLogin("");
        server.expect(requestTo(QUERY)).andRespond(withUnauthorizedRequest());
        expectLogin("");
        expectQuery();

        assertThat(client.fetch(ISS).noradId()).isEqualTo(ISS);

        server.verify();
    }

    /** One retry, never a loop: a loop would spend the whole budget proving one point. */
    @Test
    void givesUpAfterASecondRefusal() {
        SpaceTrackTleClient client = client();
        expectLogin("");
        server.expect(requestTo(QUERY)).andRespond(withUnauthorizedRequest());
        expectLogin("");
        server.expect(requestTo(QUERY)).andRespond(withUnauthorizedRequest());

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(ISS))
                .withMessageContaining("twice");

        server.verify();
    }

    /**
     * A refused login comes back <strong>200</strong> with {@code {"Login":"Failed"}}.
     * Trusting the status alone would let a wrong password look like a working session,
     * and the first sign of it would be an unreadable body three method calls later.
     */
    @Test
    void treatsARefusedLoginAsAFailureDespiteThe200() {
        SpaceTrackTleClient client = client();
        expectLogin("{\"Login\":\"Failed\"}");

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(ISS))
                .withMessageContaining("credentials");

        server.verify();
    }

    /**
     * Space-Track says "no such object" by returning nothing — the opposite of CelesTrak,
     * where an empty body is a truncated exchange. Same bytes, opposite meanings, which is
     * why each client owns that decision.
     */
    @Test
    void anEmptyResultMeansTheCatalogueDoesNotHoldIt() {
        SpaceTrackTleClient client = client();
        expectLogin("");
        server.expect(requestTo(QUERY)).andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        assertThatExceptionOfType(TleNotFoundException.class)
                .isThrownBy(() -> client.fetch(ISS));

        server.verify();
    }

    /**
     * The budget covers logins too: they are calls like any other as far as Space-Track's
     * counters are concerned. Here it is spent by the login, and the query never leaves —
     * which is the point, since blocking would hold a server thread instead.
     */
    @Test
    void refusesToCallWhenTheBudgetIsSpent() {
        SpaceTrackTleClient client = clientWithBudget(1, 1);
        expectLogin("");

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(ISS))
                .withMessageContaining("budget");

        server.verify();
    }
}
