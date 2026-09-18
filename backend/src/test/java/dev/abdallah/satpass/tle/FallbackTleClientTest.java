package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The order in which sources are asked, and what happens when they say no.
 *
 * <p>No Spring context, no network: both sources are doubles. What is checked here is a
 * decision rule — when to fall through, when to stop — not an HTTP exchange, which is
 * {@link CelestrakTleClientTest}'s subject.
 */
class FallbackTleClientTest {

    private static final int ISS = 25544;
    private static final Duration COOLDOWN = Duration.ofMinutes(10);

    private TleClient first;
    private TleClient second;
    private MutableClock clock;
    private FallbackTleClient chain;

    @BeforeEach
    void setUp() {
        first = mock(TleClient.class);
        second = mock(TleClient.class);
        clock = new MutableClock(Instant.parse("2026-09-18T21:00:00Z"));
        chain = new FallbackTleClient(List.of(first, second), COOLDOWN, clock);
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
                .isThrownBy(() -> new FallbackTleClient(List.of(), COOLDOWN, clock))
                .withMessageContaining("at least one source");
    }

    /**
     * Measured in production: the first endpoint burned its whole connect timeout on every
     * single retrieval, having never once answered from that host — two seconds taken out
     * of the budget the next source then had to answer within. A source that has just
     * failed is asked last.
     */
    @Test
    void asksADemotedSourceLastOnTheNextFetch() {
        when(first.fetch(ISS)).thenThrow(new TleUnavailableException("origin unreachable"));
        when(second.fetch(ISS)).thenReturn(snapshot());
        chain.fetch(ISS);

        clock.advance(Duration.ofMinutes(1));
        chain.fetch(ISS);

        // Called once, by the first fetch: the second fetch went straight to the source
        // that works and never paid for the one that does not.
        verify(first, times(1)).fetch(ISS);
        verify(second, times(2)).fetch(ISS);
    }

    /** Demoted, not removed: the cooldown ends and the configured order comes back. */
    @Test
    void putsTheSourceBackInFrontOnceTheCooldownHasElapsed() {
        when(first.fetch(ISS))
                .thenThrow(new TleUnavailableException("origin unreachable"))
                .thenReturn(snapshot());
        when(second.fetch(ISS)).thenReturn(snapshot());
        chain.fetch(ISS);

        clock.advance(COOLDOWN);
        chain.fetch(ISS);

        verify(first, times(2)).fetch(ISS);
        verify(second, times(1)).fetch(ISS);
    }

    /**
     * The case a breaker that <em>excludes</em> gets wrong: with every source cooling there
     * is nothing left to prefer, so the order is the configured one and every source is
     * still tried. A demotion can never manufacture an outage.
     */
    @Test
    void keepsTheConfiguredOrderWhenEverySourceIsCooling() {
        when(first.fetch(ISS)).thenThrow(new TleUnavailableException("origin unreachable"));
        when(second.fetch(ISS)).thenThrow(new TleUnavailableException("relay unreachable"));
        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> chain.fetch(ISS));

        clock.advance(Duration.ofMinutes(1));
        // doReturn and not when(...).thenReturn: the argument of when() would call the
        // stub that is already set to throw.
        doReturn(snapshot()).when(second).fetch(ISS);
        assertThat(chain.fetch(ISS).noradId()).isEqualTo(ISS);

        InOrder order = inOrder(first, second);
        order.verify(first).fetch(ISS);
        order.verify(second).fetch(ISS);
        order.verify(first).fetch(ISS);
        order.verify(second).fetch(ISS);
    }
}
