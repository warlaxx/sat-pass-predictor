package dev.abdallah.satpass.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

/**
 * An optional source has to be optional <em>at startup</em>, or it is not optional.
 */
class SpaceTrackPropertiesTest {

    private static final String URL = "https://spacetrack.test";

    @Test
    void withoutCredentialsTheSourceIsSimplyAbsent() {
        SpaceTrackProperties properties = new SpaceTrackProperties(URL, "", "", 0, 0);

        assertThat(properties.configured()).isFalse();
    }

    @Test
    void acceptsACompleteConfiguration() {
        assertThatNoException().isThrownBy(() ->
                new SpaceTrackProperties(URL, "a@b.test", "secret", 20, 200));
        assertThat(new SpaceTrackProperties(URL, "a@b.test", "secret", 20, 200).configured())
                .isTrue();
    }

    /**
     * Half a credential would otherwise disable the source in silence — the exact shape of
     * the defect this whole chain was built after.
     */
    @Test
    void refusesHalfACredential() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpaceTrackProperties(URL, "a@b.test", "  ", 20, 200))
                .withMessageContaining("both identity and password");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpaceTrackProperties(URL, null, "secret", 20, 200))
                .withMessageContaining("both identity and password");
    }

    @Test
    void refusesAnUncappedClient() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpaceTrackProperties(URL, "a@b.test", "secret", 0, 200))
                .withMessageContaining("requests-per-minute");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpaceTrackProperties(URL, "a@b.test", "secret", 400, 200))
                .withMessageContaining("requests-per-hour");
    }

    @Test
    void refusesCredentialsWithoutABaseUrl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SpaceTrackProperties(" ", "a@b.test", "secret", 20, 200))
                .withMessageContaining("base-url");
    }
}
