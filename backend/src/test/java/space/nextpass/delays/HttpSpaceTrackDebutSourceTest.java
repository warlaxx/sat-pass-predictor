package space.nextpass.delays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import space.nextpass.OrekitTest;
import space.nextpass.TleFixtures;
import space.nextpass.tle.RequestBudget;
import space.nextpass.tle.SpaceTrackSession;
import space.nextpass.tle.SpaceTrackTleClient;
import space.nextpass.tle.TleUnavailableException;

/**
 * The nightly debut query, and the reason {@link SpaceTrackSession} exists: the TLE chain
 * and this query are one account, so one cookie and one budget.
 *
 * <p>The Spring context is loaded only for the TLE client's {@link DataContext}.
 */
@OrekitTest
class HttpSpaceTrackDebutSourceTest {

    private static final String BASE_URL = "https://spacetrack.test";
    private static final String LOGIN = BASE_URL + "/ajaxauth/login";
    private static final String DEBUTS = BASE_URL
            + "/basicspacedata/query/class/satcat_debut/DEBUT/%3Enow-7/format/json";
    private static final Instant NOW = Instant.parse("2026-10-03T18:23:00Z");

    @Autowired
    DataContext dataContext;

    private MockRestServiceServer server;

    private SpaceTrackSession session(int perMinute, int perHour) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new SpaceTrackSession(BASE_URL, builder.build(),
                new RequestBudget(perMinute, perHour, Clock.fixed(NOW, ZoneOffset.UTC)),
                "pilot@example.test", "secret");
    }

    private void expectLogin() {
        server.expect(requestTo(LOGIN)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
    }

    @Test
    void asksForTheLastWeekOfDebuts() {
        var source = new HttpSpaceTrackDebutSource(session(20, 200));
        expectLogin();
        server.expect(requestTo(DEBUTS)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"NORAD_CAT_ID":"100961","INTLDES":"2026-228B","SATNAME":"USA 700",
                          "DEBUT":"2026-10-02 12:00:00"}]""", MediaType.APPLICATION_JSON));

        assertThat(source.recent()).containsExactly(new SpaceTrackDebut(100961,
                Instant.parse("2026-10-02T12:00:00Z"), "2026-228B", "USA 700"));
        server.verify();
    }

    /** Space-Track counts per account: a TLE fetch and the debut query share one login. */
    @Test
    void sharesTheSessionWithTheTleChain() {
        SpaceTrackSession session = session(20, 200);
        var tles = new SpaceTrackTleClient(session, dataContext, Clock.fixed(NOW, ZoneOffset.UTC));
        var debuts = new HttpSpaceTrackDebutSource(session);
        expectLogin();
        server.expect(requestTo(BASE_URL
                        + "/basicspacedata/query/class/gp/NORAD_CAT_ID/25544/limit/1/format/3le"))
                .andRespond(withSuccess("0 " + TleFixtures.issName() + "\r\n" + TleFixtures.issLine1()
                        + "\r\n" + TleFixtures.issLine2() + "\r\n", MediaType.TEXT_PLAIN));
        server.expect(requestTo(DEBUTS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        tles.fetch(25544);
        assertThat(debuts.recent()).isEmpty();

        server.verify();
    }

    /** And one budget: what the TLE chain spent, the debut query cannot spend again. */
    @Test
    void sharesTheBudgetWithTheTleChain() {
        SpaceTrackSession session = session(2, 2);
        var tles = new SpaceTrackTleClient(session, dataContext, Clock.fixed(NOW, ZoneOffset.UTC));
        expectLogin();
        server.expect(requestTo(BASE_URL
                        + "/basicspacedata/query/class/gp/NORAD_CAT_ID/25544/limit/1/format/3le"))
                .andRespond(withSuccess("0 " + TleFixtures.issName() + "\r\n" + TleFixtures.issLine1()
                        + "\r\n" + TleFixtures.issLine2() + "\r\n", MediaType.TEXT_PLAIN));
        tles.fetch(25544);

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> new HttpSpaceTrackDebutSource(session).recent())
                .withMessageContaining("budget");
        server.verify();
    }

    @Test
    void anErrorStatusIsUnavailable() {
        var source = new HttpSpaceTrackDebutSource(session(20, 200));
        expectLogin();
        server.expect(requestTo(DEBUTS)).andRespond(withServerError());

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(source::recent)
                .withMessageContaining("catalogue debuts");
    }
}
