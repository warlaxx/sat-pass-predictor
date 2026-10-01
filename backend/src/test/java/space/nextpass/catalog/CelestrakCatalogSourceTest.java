package space.nextpass.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import space.nextpass.TleFixtures;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The index download against what CelesTrak's group endpoint returns, without network. */
class CelestrakCatalogSourceTest {

    private static final String BASE_URL = "https://celestrak.test";
    private static final String URL = BASE_URL + "/NORAD/elements/gp.php?GROUP=active&FORMAT=TLE";

    private MockRestServiceServer server;
    private CelestrakCatalogSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        source = new CelestrakCatalogSource(BASE_URL, builder.build());
    }

    /** A record for another number: only line 1's columns 3-7 are read, so no checksum. */
    private static String record(String name, String number) {
        String line1 = "1 " + number + TleFixtures.issLine1().substring(7);
        return String.format("%-24s", name) + "\r\n" + line1 + "\r\n" + TleFixtures.issLine2() + "\r\n";
    }

    private void respondWith(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
    }

    @Test
    void readsNamesAndNumbersFromTheGroup() {
        respondWith(TleFixtures.celestrakThreeLineResponse() + record("0 HST", "20580"));

        assertThat(source.fetchAll()).containsExactly(
                new SatelliteEntry(TleFixtures.issNoradId(), TleFixtures.issName()),
                new SatelliteEntry(20580, "HST"));
        server.verify();
    }

    @Test
    void skipsAlphaFiveNumbersAndDuplicates() {
        respondWith(record("FIRST", "12345") + record("AGAIN", "12345") + record("FUTURE", "A0001"));

        assertThat(source.fetchAll()).containsExactly(new SatelliteEntry(12345, "FIRST"));
    }

    @Test
    void refusesAnHtmlPageServedIn200() {
        respondWith("<html>\n<body>\nToo many requests</body>\n</html>\n");

        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);
    }

    @Test
    void refusesTheWholeBodyWhenOneRecordIsBroken() {
        // A partial index would answer "no such satellite" for the names it lost.
        respondWith(record("GOOD", "12345") + "BROKEN\r\n2 nothing\r\n1 nothing\r\n");

        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);
    }

    @Test
    void refusesAnEmptyBody() {
        respondWith("");

        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);
    }

    @Test
    void reportsErrorsAndTimeoutsAsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatExceptionOfType(CatalogUnavailableException.class)
                .isThrownBy(source::fetchAll).withMessageContaining(BASE_URL);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);
    }
}
