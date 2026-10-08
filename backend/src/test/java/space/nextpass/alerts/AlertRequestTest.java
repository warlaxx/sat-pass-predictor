package space.nextpass.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AlertRequestTest {

    AlertRequest request(String email, String zone) {
        return new AlertRequest(email, 25544, 45.7578, 4.832, null, null, null, zone, "fr");
    }

    @Test
    void normalisesTheAddressAndFillsTheDefaults() {
        AlertRequest request = request("  Ada@Example.ORG ", "Europe/Paris");

        assertThat(request.email()).isEqualTo("ada@example.org");
        assertThat(request.minElevationDeg()).isEqualTo(30);
        assertThat(request.maxCloudPercent()).isEqualTo(25);
        assertThat(request.locale()).isEqualTo("fr");
        assertThat(new AlertRequest("a@b.co", 1, 0.0, 0.0, 10, 0, -2.0, "UTC", "de").locale()).isEqualTo("en");
    }

    @Test
    void roundsThePlaceToAboutAKilometre() {
        assertThat(AlertRequest.rounded(45.7578)).isEqualTo(45.76);
        assertThat(AlertRequest.rounded(-4.832)).isEqualTo(-4.83);
    }

    @Test
    void refusesWhatCannotBeAnAddress() {
        for (String bad : new String[] {null, "", "ada", "ada@example", "a da@example.org", "a@b@c.org"}) {
            assertThatThrownBy(() -> request(bad, "Europe/Paris")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesAnUnknownZoneOrABareOffset() {
        assertThatThrownBy(() -> request("a@b.co", "Mars/Olympus")).hasMessageContaining("not a known region");
        assertThatThrownBy(() -> request("a@b.co", "+02:00")).hasMessageContaining("not an offset");
        assertThatThrownBy(() -> request("a@b.co", null)).hasMessageContaining("missing");
    }

    @Test
    void refusesThresholdsOutOfBounds() {
        assertThatThrownBy(() -> new AlertRequest("a@b.co", 25544, 45.0, 5.0, 5, null, null, "UTC", "en"))
                .hasMessageContaining("minElevationDeg");
        assertThatThrownBy(() -> new AlertRequest("a@b.co", 25544, 45.0, 5.0, null, 101, null, "UTC", "en"))
                .hasMessageContaining("maxCloudPercent");
        assertThatThrownBy(() -> new AlertRequest("a@b.co", 0, 45.0, 5.0, null, null, null, "UTC", "en"))
                .hasMessageContaining("noradId");
        assertThatThrownBy(() -> new AlertRequest("a@b.co", 25544, 91.0, 5.0, null, null, null, "UTC", "en"))
                .hasMessageContaining("lat");
    }
}
