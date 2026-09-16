package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
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
import org.springframework.web.client.RestClient;

/**
 * Le client face a ce que CelesTrak renvoie vraiment.
 *
 * <p>Aucun appel reseau : {@link MockRestServiceServer} intercepte les requetes. Un test
 * qui interrogerait CelesTrak echouerait le jour ou le service est lent, et ses
 * assertions changeraient a chaque republication de TLE — plusieurs fois par jour.
 *
 * <p>Le contexte Spring n'est charge que pour {@link DataContext} : la conversion de
 * l'epoque d'un TLE en {@link Instant} passe par les echelles de temps d'Orekit, donc
 * par {@code orekit-data}.
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
                builder.build(), dataContext, Clock.fixed(FETCHED_AT, ZoneOffset.UTC));
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

        // Champ d'epoque du TLE de reference : 21035.14486477, soit le 35e jour de 2021
        // a 0,14486477 jour. L'ecart avec fetchedAt est tout l'interet du champ : l'age
        // d'un TLE se compte depuis son epoque, pas depuis l'appel HTTP.
        assertThat(snapshot.epoch())
                .isCloseTo(Instant.parse("2021-02-04T03:28:36.316Z"), within(1, ChronoUnit.MILLIS));
        assertThat(snapshot.epoch()).isBefore(snapshot.fetchedAt());
    }

    /**
     * Le piege principal de l'API GP : un numero inconnu ne donne pas un 404 mais un 200
     * dont le corps est une phrase en anglais. Un client qui ne regarde que le code HTTP
     * essaierait de parser « No GP data found » comme un TLE.
     */
    @Test
    void treatsTheNoGpDataBodyAsAnUnknownSatellite() {
        respondWith("No GP data found\r\n");

        assertThatExceptionOfType(TleNotFoundException.class)
                .isThrownBy(() -> client.fetch(25544))
                .satisfies(e -> assertThat(e.noradId()).isEqualTo(25544));
        server.verify();
    }

    /** Sous forte charge, CelesTrak sert une page HTML, toujours en 200. */
    @Test
    void treatsAnHtmlPageAsAnOutage() {
        respondWith("<html><body>Service temporarily unavailable</body></html>");

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544));
    }

    /**
     * Le numero renvoye est verifie contre celui demande. Sans ce controle, une reponse
     * mise en cache par un intermediaire pour un autre satellite produirait des passages
     * parfaitement plausibles — et faux.
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
     * Une ligne alteree en transit doit echouer a la recuperation, pas au milieu d'une
     * propagation SGP4. Orekit ne verifie pas la somme de controle a la construction :
     * ce TLE corrompu lui passe sans broncher, c'est le client qui le refuse.
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
                .withCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    @Test
    void translatesAServerErrorIntoAnOutage() {
        server.expect(requestTo(BASE_URL + "/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"))
                .andRespond(withServerError());

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> client.fetch(25544))
                .withMessageContaining("500");
    }
}
