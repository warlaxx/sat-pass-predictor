package space.nextpass.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import space.nextpass.TleFixtures;
import space.nextpass.domain.TleSnapshot;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The index download against what CelesTrak's group endpoint returns, without network. */
class CelestrakCatalogSourceTest {

    private static final String BASE_URL = "https://celestrak.test";
    private static final String URL = BASE_URL + "/NORAD/elements/gp.php?GROUP=active&FORMAT=JSON";

    private MockRestServiceServer server;
    private CelestrakCatalogSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        source = new CelestrakCatalogSource(BASE_URL, builder.build());
    }

    /** A record of the group: the three fields read, and an element the index ignores. */
    private static String record(String name, String objectId, int number) {
        return "{\"OBJECT_NAME\":\"" + name + "\",\"OBJECT_ID\":\"" + objectId
                + "\",\"EPOCH\":\"2026-10-01T22:11:24.452448\",\"MEAN_MOTION\":15.7759124,"
                + "\"NORAD_CAT_ID\":" + number + "}";
    }

    private static String group(String... records) {
        return "[" + String.join(",", records) + "]";
    }

    private void respondWith(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void readsNamesNumbersAndLaunchesFromTheGroup() {
        respondWith(group(record("ISS (ZARYA)", "1998-067A", 25544), record("HST", "1990-037B", 20580)));

        assertThat(source.fetchAll()).containsExactly(
                new SatelliteEntry(25544, "ISS (ZARYA)", "1998-067"),
                new SatelliteEntry(20580, "HST", "1990-037"));
        server.verify();
    }

    /**
     * The reason the index moved to JSON: since July 2026 new objects have six-digit
     * numbers, and the TLE form of the group leaves them out. Read from CelesTrak's real
     * answer for one of them.
     */
    @Test
    void keepsSixDigitNumbers() {
        respondWith(TleFixtures.STARLINK_100534_OMM);

        assertThat(source.fetchAll()).containsExactly(new SatelliteEntry(100534, "STARLINK-38244", "2026-200"));
    }

    @Test
    void skipsNumbersNoTleCanCarryAndDuplicates() {
        respondWith(group(record("FIRST", "2026-001A", 12345), record("AGAIN", "2026-001A", 12345),
                record("LAST", "2026-002A", TleSnapshot.MAX_NORAD_ID),
                record("BEYOND", "2026-003A", TleSnapshot.MAX_NORAD_ID + 1)));

        assertThat(source.fetchAll()).containsExactly(
                new SatelliteEntry(12345, "FIRST", "2026-001"),
                new SatelliteEntry(TleSnapshot.MAX_NORAD_ID, "LAST", "2026-002"));
    }

    @Test
    void readsTheLaunchOfTheInternationalDesignator() {
        assertThat(CelestrakCatalogSource.launch("2026-045A")).isEqualTo("2026-045");
        assertThat(CelestrakCatalogSource.launch("1958-002B")).isEqualTo("1958-002");
        assertThat(CelestrakCatalogSource.launch("1998-067AAB")).isEqualTo("1998-067");
        // Analyst objects and unknowns carry no designator: no launch, not a refusal.
        assertThat(CelestrakCatalogSource.launch("")).isNull();
        assertThat(CelestrakCatalogSource.launch("UNKNOWN")).isNull();
        assertThat(CelestrakCatalogSource.launch(null)).isNull();
    }

    @Test
    void refusesAnHtmlPageServedIn200() {
        respondWith("<html>\n<body>\nToo many requests</body>\n</html>\n");

        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);
    }

    /** What CelesTrak answers a second download of a group within its two hours. */
    @Test
    void refusesTheNotUpdatedNotice() {
        respondWith("GP data has not updated since your last successful\n"
                + "download of GROUP=active at 2026-10-02 17:10:05 UTC.\n"
                + "Data is updated once every 2 hours.\n");

        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);
    }

    @Test
    void refusesTheWholeBodyWhenOneRecordIsBroken() {
        // A partial index would answer "no such satellite" for the names it lost.
        respondWith(group(record("GOOD", "2026-001A", 12345), "{\"OBJECT_NAME\":\"NO NUMBER\"}"));
        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);

        server.reset();
        respondWith(group(record("GOOD", "2026-001A", 12345), record("", "2026-001B", 12346)));
        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);

        server.reset();
        respondWith(group(record("GOOD", "2026-001A", 12345)).replace("]", ",{\"OBJECT_NAME\":"));
        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);
    }

    @Test
    void refusesAnEmptyBodyOrAnEmptyGroup() {
        respondWith("");
        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(source::fetchAll);

        server.reset();
        respondWith("[]");
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
