package space.nextpass.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import space.nextpass.tle.MutableClock;

class CloudCoverServiceTest {

    static final String MET = "https://api.met.no/weatherapi/locationforecast/2.0/compact";
    static final String LYON = MET + "?lat=45.8&lon=4.8";
    static final Instant NOW = MetNorwayParserTest.NOW;

    MockRestServiceServer server;
    MutableClock clock;
    CloudCoverService service;
    String body;

    @BeforeEach
    void setUp() throws IOException {
        RestClient.Builder builder = RestClient.builder().baseUrl(MET);
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(NOW);
        service = new CloudCoverService(builder.build(), clock, 3, 2);
        body = MetNorwayParserTest.fixture("lyon.json");
    }

    private static HttpHeaders validators(String expires) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.EXPIRES, expires);
        headers.set(HttpHeaders.LAST_MODIFIED, "Wed, 07 Oct 2026 10:37:43 GMT");
        return headers;
    }

    @Test
    void asksForTheRoundedCellAndKeepsItUntilMetSaysSo() {
        server.expect(requestTo(LYON)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)
                .headers(validators("Wed, 07 Oct 2026 11:09:27 GMT")));

        CloudCoverService.Result first = service.forecast(45.7578, 4.832);
        // A neighbour two kilometres away, twenty minutes later: the same entry, no request.
        clock.advance(Duration.ofMinutes(20));
        CloudCoverService.Result second = service.forecast(45.77, 4.85);

        server.verify();
        assertThat(first.forecast().latitudeDeg()).isEqualTo(45.8);
        assertThat(first.forecast().longitudeDeg()).isEqualTo(4.8);
        assertThat(first.forecast().hours()).hasSize(5);
        assertThat(first.expires()).isEqualTo(Instant.parse("2026-10-07T11:09:27Z"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void anExpiredForecastIsAskedAgainConditionally() {
        server.expect(requestTo(LYON)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)
                .headers(validators("Wed, 07 Oct 2026 11:09:27 GMT")));
        server.expect(requestTo(LYON))
                .andExpect(header(HttpHeaders.IF_MODIFIED_SINCE, "Wed, 07 Oct 2026 10:37:43 GMT"))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).headers(validators("Wed, 07 Oct 2026 11:45:00 GMT")));

        CloudCoverService.Result first = service.forecast(45.76, 4.83);
        clock.advance(Duration.ofMinutes(40));
        CloudCoverService.Result second = service.forecast(45.76, 4.83);

        server.verify();
        assertThat(second.forecast()).isEqualTo(first.forecast());
        assertThat(second.expires()).isEqualTo(Instant.parse("2026-10-07T11:45:00Z"));
    }

    @Test
    void metFailingServesTheLastForecastAndWaitsBeforeAskingAgain() {
        server.expect(requestTo(LYON)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)
                .headers(validators("Wed, 07 Oct 2026 10:40:00 GMT")));
        server.expect(requestTo(LYON)).andRespond(withServerError());

        CloudCoverService.Result first = service.forecast(45.76, 4.83);
        clock.advance(Duration.ofMinutes(15));
        CloudCoverService.Result stale = service.forecast(45.76, 4.83);

        server.verify();
        assertThat(stale.forecast()).isEqualTo(first.forecast());
        assertThat(stale.expires()).isEqualTo(clock.instant().plus(CloudCoverService.MIN_KEEP));
    }

    @Test
    void aPlaceNeverFetchedIsUnavailableWhenMetFails() {
        server.expect(requestTo(LYON)).andRespond(withSuccess("Too many requests", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> service.forecast(45.76, 4.83)).isInstanceOf(WeatherUnavailableException.class);
    }

    /** Anyone can ask for any place: past the budget, MET is not asked at all. */
    @Test
    void theBudgetCapsWhatMetIsAskedEachMinute() {
        for (String lat : new String[] {"10.0", "20.0", "30.0"}) {
            server.expect(requestTo(MET + "?lat=" + lat + "&lon=0.0"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        server.expect(never(), requestTo(MET + "?lat=40.0&lon=0.0"));

        service.forecast(10, 0);
        service.forecast(20, 0);
        service.forecast(30, 0);
        assertThatThrownBy(() -> service.forecast(40, 0)).isInstanceOf(WeatherUnavailableException.class);

        server.verify();
        // And the cache stays at its size, whatever was asked.
        assertThat(service.cachedCells()).isEqualTo(2);
    }

    @Test
    void anExpiresOutsideTheBoundsIsClamped() {
        server.expect(requestTo(LYON)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)
                .headers(validators("Thu, 08 Oct 2026 10:30:00 GMT")));

        assertThat(service.forecast(45.76, 4.83).expires()).isEqualTo(NOW.plus(CloudCoverService.MAX_KEEP));
    }

    @Test
    void cellsRoundToATenthOfADegreeAndWrapAtTheAntimeridian() {
        assertThat(CloudCoverService.Cell.of(45.7578, 4.832)).isEqualTo(new CloudCoverService.Cell(45.8, 4.8));
        assertThat(CloudCoverService.Cell.of(-33.92, 18.42)).isEqualTo(new CloudCoverService.Cell(-33.9, 18.4));
        assertThat(CloudCoverService.Cell.of(0, 179.97)).isEqualTo(new CloudCoverService.Cell(0, -180));
        assertThat(CloudCoverService.Cell.of(0, -0.04).longitude()).isEqualTo("0.0");
    }
}
