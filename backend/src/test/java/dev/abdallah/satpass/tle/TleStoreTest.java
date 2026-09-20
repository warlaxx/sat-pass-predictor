package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.config.TleProperties;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The TLE store: when it calls CelesTrak back, and what it does when CelesTrak does not
 * answer.
 *
 * <p>No Spring context, no network: the client is a double, the clock is advanced by
 * hand. The rules checked here are decision rules, not orbital computation.
 */
class TleStoreTest {

    private static final int ISS = 25544;
    private static final Instant EPOCH = Instant.parse("2021-02-04T03:28:36.316Z");
    private static final Instant START = Instant.parse("2021-02-04T08:00:00Z");

    private static final Duration REFRESH_AFTER = Duration.ofHours(2);
    private static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private TleClient client;
    private MutableClock clock;
    private TleStore store;

    @BeforeEach
    void setUp() {
        client = mock(TleClient.class);
        clock = new MutableClock(START);
        store = new TleStore(client, properties(), TleSnapshotRepository.NONE, clock);
    }

    private static TleProperties properties() {
        return new TleProperties(List.of("https://celestrak.test"),
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10),
                REFRESH_AFTER, RETRY_AFTER, MAX_AGE, 500);
    }

    /** A snapshot whose epoch and fetch date are chosen by the test. */
    private static TleSnapshot snapshot(Instant epoch, Instant fetchedAt) {
        return new TleSnapshot(ISS, TleFixtures.issName(), TleFixtures.issLine1(),
                TleFixtures.issLine2(), epoch, fetchedAt, "celestrak");
    }

    @Test
    void callsCelestrakOnceForRepeatedRequestsWithinTheRefreshWindow() {
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));

        store.get(ISS);
        clock.advance(Duration.ofMinutes(119));
        TleSnapshot second = store.get(ISS);

        verify(client, times(1)).fetch(ISS);
        assertThat(second.fetchedAt()).isEqualTo(START);
    }

    @Test
    void callsCelestrakAgainOncePastTheRefreshWindow() {
        Instant later = START.plus(REFRESH_AFTER);
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenReturn(snapshot(EPOCH.plus(Duration.ofHours(2)), later));

        store.get(ISS);
        clock.advance(REFRESH_AFTER);
        TleSnapshot refreshed = store.get(ISS);

        verify(client, times(2)).fetch(ISS);
        assertThat(refreshed.fetchedAt()).isEqualTo(later);
    }

    /**
     * The heart of the design decision: the two-hour window triggers an attempt, not an
     * eviction. With a TTL cache this test could not pass — there would be nothing left
     * to serve.
     */
    @Test
    void keepsServingTheLastKnownTleWhenCelestrakIsDown() {
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenThrow(new TleUnavailableException("CelesTrak unreachable"));

        store.get(ISS);
        clock.advance(Duration.ofHours(6));
        TleSnapshot served = store.get(ISS);

        assertThat(served.fetchedAt()).isEqualTo(START);
        assertThat(served.ageSinceEpoch(clock.instant())).isGreaterThan(Duration.ofHours(6));
    }

    /**
     * A failed refresh leaves the snapshot, and therefore its fetch date, unchanged. The
     * staleness test stays true, so without a backoff every single request during an
     * outage would call CelesTrak again — which CelesTrak explicitly asks callers not to
     * do. The entry therefore remembers its last attempt, not just its last success.
     */
    @Test
    void doesNotCallCelestrakAgainWhileTheBackoffWindowIsOpen() {
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenThrow(new TleUnavailableException("CelesTrak unreachable"));

        store.get(ISS);
        clock.advance(REFRESH_AFTER);
        store.get(ISS); // second call: the failing attempt

        clock.advance(RETRY_AFTER.minusMinutes(1));
        TleSnapshot served = store.get(ISS);
        store.get(ISS);

        verify(client, times(2)).fetch(ISS);
        assertThat(served.fetchedAt()).isEqualTo(START);
    }

    @Test
    void triesAgainOnceTheBackoffWindowHasElapsed() {
        Instant recovered = START.plus(REFRESH_AFTER).plus(RETRY_AFTER);
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenThrow(new TleUnavailableException("CelesTrak unreachable"))
                .thenReturn(snapshot(EPOCH.plus(Duration.ofHours(2)), recovered));

        store.get(ISS);
        clock.advance(REFRESH_AFTER);
        store.get(ISS); // the failing attempt

        clock.advance(RETRY_AFTER);
        TleSnapshot refreshed = store.get(ISS);

        verify(client, times(3)).fetch(ISS);
        assertThat(refreshed.fetchedAt()).isEqualTo(recovered);
    }

    /** Degrading assumes there is something to degrade to. */
    @Test
    void failsWhenCelestrakIsDownAndNothingWasEverFetched() {
        when(client.fetch(ISS)).thenThrow(new TleUnavailableException("CelesTrak unreachable"));

        assertThatExceptionOfType(TleUnavailableException.class).isThrownBy(() -> store.get(ISS));
    }

    @Test
    void collapsesConcurrentFailuresWhenNothingIsCached() throws Exception {
        when(client.fetch(ISS)).thenThrow(new TleUnavailableException("connect timed out"));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> {
                        try {
                            store.get(ISS);
                            return false;
                        } catch (TleUnavailableException expected) {
                            return true;
                        }
                    }).toList();
            for (var result : executor.invokeAll(tasks)) {
                assertThat(result.get()).isTrue();
            }
        }
        verify(client, times(1)).fetch(ISS);
    }

    @Test
    void backsOffAnEmptyStoreThenRecovers() {
        when(client.fetch(ISS))
                .thenThrow(new TleUnavailableException("connect timed out"))
                .thenReturn(snapshot(EPOCH, START.plusSeconds(15)));
        assertThatExceptionOfType(TleUnavailableException.class).isThrownBy(() -> store.get(ISS));
        clock.advance(Duration.ofSeconds(14));
        assertThatExceptionOfType(TleUnavailableException.class).isThrownBy(() -> store.get(ISS));
        verify(client, times(1)).fetch(ISS);
        clock.advance(Duration.ofSeconds(1));
        assertThat(store.get(ISS).fetchedAt()).isEqualTo(START.plusSeconds(15));
        verify(client, times(2)).fetch(ISS);
    }

    /**
     * A satellite removed from the catalogue has most likely re-entered the atmosphere.
     * Serving its last TLE would display the passes of an object that no longer exists:
     * degradation stops here, and the entry is forgotten.
     */
    @Test
    void forgetsASatelliteThatLeavesTheCatalogue() {
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenThrow(new TleNotFoundException(ISS));

        store.get(ISS);
        clock.advance(REFRESH_AFTER);

        assertThatExceptionOfType(TleNotFoundException.class).isThrownBy(() -> store.get(ISS));
        // The entry is really gone: the next call asks CelesTrak again instead of serving
        // the old TLE, even before the end of the refresh window.
        assertThatExceptionOfType(TleNotFoundException.class).isThrownBy(() -> store.get(ISS));
        verify(client, times(3)).fetch(ISS);
    }

    /**
     * Degradation has a limit, and it bears on the epoch of the elements, not on the date
     * of the HTTP call: it is the physical age that makes SGP4 diverge.
     */
    @Test
    void refusesATleWhoseElementsAreOlderThanTheHardLimit() {
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenThrow(new TleUnavailableException("CelesTrak unreachable"));

        store.get(ISS);
        clock.advance(MAX_AGE);

        assertThatExceptionOfType(TleTooOldException.class).isThrownBy(() -> store.get(ISS));
    }

    /**
     * CelesTrak explicitly asks callers not to hammer its API. Ten concurrent requests
     * for the same satellite must produce one call, not ten: that is what the per-key
     * atomicity of {@code asMap().compute} guarantees.
     */
    @Test
    void collapsesConcurrentRequestsForTheSameSatelliteIntoOneCall() throws Exception {
        int callers = 10;
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);

        when(client.fetch(anyInt())).thenAnswer(invocation -> {
            fetches.incrementAndGet();
            Thread.sleep(50); // gives the other callers time to arrive
            return snapshot(EPOCH, START);
        });

        List<Thread> threads = IntStream.range(0, callers)
                .mapToObj(i -> Thread.ofPlatform().unstarted(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    store.get(ISS);
                }))
                .toList();

        threads.forEach(Thread::start);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }

        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void doesNotCallCelestrakAtAllWhenTheStoreIsFresh() {
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));
        store.get(ISS);

        clock.advance(Duration.ofMinutes(1));
        store.get(ISS);
        store.get(ISS);

        verify(client, times(1)).fetch(ISS);
        verify(client, never()).fetch(99999);
    }
}
