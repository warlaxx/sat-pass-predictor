package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The order in which sources are asked, and what happens when they say no.
 *
 * <p>No Spring context, no network: both sources are doubles. What is checked here is a
 * decision rule — when to fall through, when to stop — not an HTTP exchange, which is
 * {@link CelestrakTleClientTest}'s subject.
 */
class FallbackTleClientTest {

    private static final int ISS = 25544;

    private TleClient first;
    private TleClient second;
    private FallbackTleClient chain;

    @BeforeEach
    void setUp() {
        first = mock(TleClient.class);
        second = mock(TleClient.class);
        chain = new FallbackTleClient(List.of(first, second));
    }

    private static TleSnapshot snapshot() {
        Instant now = Instant.parse("2021-02-04T08:00:00Z");
        return new TleSnapshot(ISS, TleFixtures.issName(), TleFixtures.issLine1(),
                TleFixtures.issLine2(), now, now, "celestrak");
    }

    /** The second source is a fallback, not a second opinion. */
    @Test
    void stopsAtTheFirstSourceThatAnswers() {
        when(first.fetch(ISS)).thenReturn(snapshot());

        assertThat(chain.fetch(ISS).noradId()).isEqualTo(ISS);

        verify(second, never()).fetch(anyInt());
    }

    @Test
    void fallsThroughToTheNextSourceWhenTheFirstIsUnavailable() {
        when(first.fetch(ISS)).thenThrow(new TleUnavailableException("origin unreachable"));
        when(second.fetch(ISS)).thenReturn(snapshot());

        assertThat(chain.fetch(ISS).noradId()).isEqualTo(ISS);

        verify(first).fetch(ISS);
        verify(second).fetch(ISS);
    }

    /**
     * Every failure survives into the thrown exception. Keeping only the last one would
     * hide the origin's diagnosis behind the relay's, and those are two repairs in two
     * different places — which is the mistake this chain was built after, not before.
     */
    @Test
    void reportsEveryFailureWhenNoSourceAnswers() {
        TleUnavailableException fromOrigin = new TleUnavailableException("origin unreachable");
        TleUnavailableException fromRelay = new TleUnavailableException("relay answered 502");
        when(first.fetch(ISS)).thenThrow(fromOrigin);
        when(second.fetch(ISS)).thenThrow(fromRelay);

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> chain.fetch(ISS))
                .withMessageContaining("2 tried")
                .satisfies(thrown -> {
                    assertThat(thrown.getCause()).isSameAs(fromOrigin);
                    assertThat(fromOrigin.getSuppressed()).containsExactly(fromRelay);
                });
    }

    /**
     * An unknown satellite is an answer, and every configured endpoint serves the same
     * catalogue. Falling through would ask one service the same question twice, and turn
     * a clean 404 into a slow one.
     *
     * <p>This is the assertion to revisit the day a genuinely different catalogue joins
     * the list — deliberately, by changing this test first.
     */
    @Test
    void stopsAtAnUnknownSatelliteWithoutAskingTheNextSource() {
        when(first.fetch(ISS)).thenThrow(new TleNotFoundException(ISS));

        assertThatExceptionOfType(TleNotFoundException.class)
                .isThrownBy(() -> chain.fetch(ISS));

        verify(second, never()).fetch(anyInt());
    }

    @Test
    void refusesAChainWithNoSource() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FallbackTleClient(List.of()))
                .withMessageContaining("at least one source");
    }
}
