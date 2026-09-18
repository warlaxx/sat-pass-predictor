package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.abdallah.satpass.OrekitTest;
import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The client against what CelesTrak really returns.
 *
 * <p>No network call: {@link MockRestServiceServer} intercepts the requests. A test that
 * queried CelesTrak would fail the day the service is slow, and its assertions would
 * change on every republication of a TLE — several times a day.
 *
 * <p>The Spring context is loaded only for {@link DataContext}: converting a TLE's epoch
 * into an {@link Instant} goes through Orekit's time scales, hence through
 * {@code orekit-data}.
 */
@OrekitTest
class CelestrakTleClientTest {

    private static final String BASE_URL = "https://celestrak.test";
    private static final Instant FETCHED_AT = Instant.parse("2021-02-04T08:00:00Z");

    @Autowired
    DataContext dataContext;

    private MockRestServiceServer server;
    private CelestrakTleClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CelestrakTleClient(
                BASE_URL, builder.build(), dataContext, Clock.fixed(FETCHED_AT, ZoneOffset.UTC));
    }

    private void respondWith(String body) {
        server.expect(requestTo(BASE_URL + "/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
    }

    @Test
    void readsTheThreeLineResponse() {
        respondWith(TleFixtures.celestrakThreeLineResponse());

        TleSnapshot snapshot = client.fetch(25544);

        server.verify();
        assertThat(snapshot.noradId()).isEqualTo(25544);
        assertThat(snapshot.name()).isEqualTo(TleFixtures.issName());
        assertThat(snapshot.line1()).isEqualTo(TleFixtures.issLine1());
        assertThat(snapshot.line2()).isEqualTo(TleFixtures.issLine2());
        assertThat(snapshot.source()).isEqualTo("celestrak");
        assertThat(snapshot.fetchedAt()).isEqualTo(FETCHED_AT);
    }

    @Test
    void readsTheEpochFromTheElementsRatherThanTheClock() {
        respondWith(TleFixtures.celestrakThreeLineResponse());

        TleSnapshot snapshot = client.fetch(25544);

        // Epoch field of the reference TLE: 21035.14486477, that is the 35th day of 2021
        // at 0.14486477 day. The gap with fetchedAt is the whole point of the field: the
        // age of a TLE counts from its epoch, not from the HTTP call.
        assertThat(snapshot.epoch())
                .isCloseTo(Instant.parse("2021-02-04T03:28:36.316Z"), within(1, ChronoUnit.MILLIS));
        assertThat(snapshot.epoch()).isBefore(snapshot.fetchedAt());
    }

    /**
     * The main trap of the GP API: an unknown number does not give a 404 but a 200 whose
     * body is an English sentence. A client that only looks at the HTTP status would try
     * to parse "No GP data found" as a TLE.
     */
    @Test
    void treatsTheNoGpDataBodyAsAnUnknownSatellite() {
        respondWith("No GP data found\r\n");

        assertThatExceptionOfType(TleNotFoundException.class)
                .isThrownBy(() -> client.fetch(25544))
                .satisfies(e -> assertThat(e.noradId()).isEqualTo(25544));
        server.verify();
    }

    /**
     * An empty body is an outage, not a statement about the catalogue.
     *
     * <p>The distinction decides what the store does next: a not-found is permanent and
     * makes it drop the satellite, so reporting a truncated response that way would throw
     * away a perfectly valid cached TLE and tell the user the satellite does not exist.
     */
    @Test
    void treatsAnEmptyBodyAsAnOutageRatherThanAnUnknownSatellite() {
        respondWith("");

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544))
                .withMessageContaining("empty");
        server.verify();
    }

    /** Same reasoning for a body that holds nothing but whitespace. */
    @Test
    void treatsABlankBodyAsAnOutage() {
        respondWith("\r\n   \r\n");

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544));
    }

    /** Under heavy load, CelesTrak serves an HTML page, still in 200. */
    @Test
    void treatsAnHtmlPageAsAnOutage() {
        respondWith("<html><body>Service temporarily unavailable</body></html>");

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544));
    }

    /**
     * The returned number is checked against the requested one. Without that check, a
     * response cached by an intermediary for another satellite would produce perfectly
     * plausible passes — and wrong ones.
     */
    @Test
    void refusesAResponseForAnotherSatellite() {
        server.expect(requestTo(BASE_URL + "/NORAD/elements/gp.php?CATNR=25545&FORMAT=TLE"))
                .andRespond(withSuccess(TleFixtures.celestrakThreeLineResponse(), MediaType.TEXT_PLAIN));

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25545))
                .withMessageContaining("25544");
    }

    /**
     * A line altered in transit must fail at retrieval, not in the middle of an SGP4
     * propagation. Orekit does not verify the checksum on construction: this corrupted
     * TLE goes through it without complaint, and it is the client that refuses it.
     */
    @Test
    void refusesLinesWhoseChecksumDoesNotMatch() {
        String line1 = TleFixtures.issLine1();
        char lastDigit = line1.charAt(line1.length() - 1);
        String corrupted = line1.substring(0, line1.length() - 1)
                + (lastDigit == '0' ? '1' : (char) (lastDigit - 1));
        respondWith(String.format("%-24s", TleFixtures.issName()) + "\r\n"
                + corrupted + "\r\n" + TleFixtures.issLine2() + "\r\n");

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544));
    }

    @Test
    void translatesATimeoutIntoAnOutage() {
        server.expect(requestTo(BASE_URL + "/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544))
                .withCauseInstanceOf(ResourceAccessException.class);
    }

    @Test
    void translatesAServerErrorIntoAnOutage() {
        server.expect(requestTo(BASE_URL + "/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"))
                .andRespond(withServerError());

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544))
                .withMessageContaining("500");
    }

    /**
     * A 404 is an outage, not an unknown satellite. The GP API never answers 404 for an
     * object it does not hold — it answers 200 with {@code No GP data found}. So a 404
     * comes from something else on the path: a relay whose route was removed, a proxy
     * that renamed the endpoint. Reading it as "not found" would make the store drop the
     * satellite and the cached TLE with it, on the strength of a misrouted request.
     */
    @Test
    void treatsA404AsAnOutageAndNotAsAnUnknownSatellite() {
        server.expect(requestTo(BASE_URL + "/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"))
                .andRespond(withResourceNotFound());

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544))
                .withMessageContaining("404");
    }

    /** Which endpoint failed is part of the diagnosis when several are configured. */
    @Test
    void namesTheEndpointItFailedOn() {
        server.expect(requestTo(BASE_URL + "/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544))
                .withMessageContaining(BASE_URL);
    }
}
