package dev.abdallah.satpass.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The relations between the four durations are business rules with an explanation
 * attached, not defensive boilerplate: each one describes a configuration that would make
 * the store behave in a way nobody wants. A rule worth a message is worth a test.
 */
class TlePropertiesTest {

    private static TleProperties properties(Duration refreshAfter, Duration retryAfter, Duration maxAge) {
        return new TleProperties(List.of("https://celestrak.test"),
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10), refreshAfter, retryAfter, maxAge, 500);
    }

    @Test
    void acceptsAConsistentConfiguration() {
        assertThatNoException().isThrownBy(() ->
                properties(Duration.ofHours(2), Duration.ofMinutes(5), Duration.ofDays(7)));
    }

    /** A TLE rejected before it ever had a chance to be refreshed. */
    @Test
    void refusesAMaxAgeBelowTheRefreshWindow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(Duration.ofHours(2), Duration.ofMinutes(5), Duration.ofHours(1)))
                .withMessageContaining("max-age");
    }

    /** A backoff longer than the refresh window would hold back ordinary refreshes too. */
    @Test
    void refusesARetryWindowLongerThanTheRefreshWindow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(Duration.ofHours(2), Duration.ofHours(3), Duration.ofDays(7)))
                .withMessageContaining("retry-after");
    }

    @Test
    void refusesANonPositiveRefreshWindow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(Duration.ZERO, Duration.ofMinutes(5), Duration.ofDays(7)))
                .withMessageContaining("refresh-after");
    }

    @Test
    void refusesANonPositiveStoreSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TleProperties(List.of("https://celestrak.test"),
                        Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10),
                        Duration.ofHours(2), Duration.ofMinutes(5), Duration.ofDays(7), 0))
                .withMessageContaining("maximum-size");
    }

    @Test
    void refusesANegativeSourceCooldown() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TleProperties(List.of("https://celestrak.test"),
                        Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(-1),
                        Duration.ofHours(2), Duration.ofMinutes(5), Duration.ofDays(7), 500))
                .withMessageContaining("source-cooldown");
    }

    @Test
    void refusesAnEmptySourceList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TleProperties(List.of(),
                        Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10),
                        Duration.ofHours(2), Duration.ofMinutes(5), Duration.ofDays(7), 500))
                .withMessageContaining("base-urls");
    }

    /**
     * A blank entry is what a trailing comma in {@code TLE_BASE_URLS} produces. It would
     * otherwise become a {@code RestClient} with no base URL, failing at the first call
     * with a message about a relative URI rather than about the configuration.
     */
    @Test
    void refusesABlankSourceInTheList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TleProperties(List.of("https://celestrak.test", "  "),
                        Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10),
                        Duration.ofHours(2), Duration.ofMinutes(5), Duration.ofDays(7), 500))
                .withMessageContaining("base-urls");
    }
}
