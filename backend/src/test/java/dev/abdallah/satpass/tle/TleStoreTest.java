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
 * Le magasin de TLE : quand il rappelle CelesTrak, et ce qu'il fait quand CelesTrak ne
 * repond pas.
 *
 * <p>Pas de contexte Spring, pas de reseau : le client est double, l'horloge est
 * avancee a la main. Les regles verifiees ici sont des regles de decision, pas du
 * calcul orbital.
 */
class TleStoreTest {

    private static final int ISS = 25544;
    private static final Instant EPOCH = Instant.parse("2021-02-04T03:28:36.316Z");
    private static final Instant START = Instant.parse("2021-02-04T08:00:00Z");

    private static final Duration REFRESH_AFTER = Duration.ofHours(2);
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private CelestrakTleClient client;
    private MutableClock clock;
    private TleStore store;

    @BeforeEach
    void setUp() {
        client = mock(CelestrakTleClient.class);
        clock = new MutableClock(START);
        store = new TleStore(client, properties(), clock);
    }

    private static TleProperties properties() {
        return new TleProperties("https://celestrak.test",
                Duration.ofSeconds(3), Duration.ofSeconds(5), REFRESH_AFTER, MAX_AGE, 500);
    }

    /** Un snapshot dont l'epoque et la date de recuperation sont choisies par le test. */
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
     * Le coeur de la decision de conception : la fenetre de deux heures declenche une
     * tentative, pas une eviction. Avec un cache a TTL, ce test ne pourrait pas passer —
     * il n'y aurait plus rien a servir.
     */
    @Test
    void keepsServingTheLastKnownTleWhenCelestrakIsDown() {
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenThrow(new TleUnavailableException("CelesTrak injoignable"));

        store.get(ISS);
        clock.advance(Duration.ofHours(6));
        TleSnapshot served = store.get(ISS);

        assertThat(served.fetchedAt()).isEqualTo(START);
        assertThat(served.ageSinceEpoch(clock.instant())).isGreaterThan(Duration.ofHours(6));
    }

    /** Degrader suppose avoir quelque chose a degrader. */
    @Test
    void failsWhenCelestrakIsDownAndNothingWasEverFetched() {
        when(client.fetch(ISS)).thenThrow(new TleUnavailableException("CelesTrak injoignable"));

        assertThatExceptionOfType(TleUnavailableException.class).isThrownBy(() -> store.get(ISS));
    }

    /**
     * Un satellite retire du catalogue est le plus souvent rentre dans l'atmosphere.
     * Servir son dernier TLE afficherait les passages d'un objet qui n'existe plus : la
     * degradation s'arrete ici, et l'entree est oubliee.
     */
    @Test
    void forgetsASatelliteThatLeavesTheCatalogue() {
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenThrow(new TleNotFoundException(ISS));

        store.get(ISS);
        clock.advance(REFRESH_AFTER);

        assertThatExceptionOfType(TleNotFoundException.class).isThrownBy(() -> store.get(ISS));
        // L'entree a bien disparu : l'appel suivant redemande a CelesTrak au lieu de
        // resservir l'ancien TLE, meme avant la fin de la fenetre de rafraichissement.
        assertThatExceptionOfType(TleNotFoundException.class).isThrownBy(() -> store.get(ISS));
        verify(client, times(3)).fetch(ISS);
    }

    /**
     * La degradation a une limite, et elle porte sur l'epoque des elements, pas sur la
     * date de l'appel HTTP : c'est l'age physique qui fait diverger SGP4.
     */
    @Test
    void refusesATleWhoseElementsAreOlderThanTheHardLimit() {
        when(client.fetch(ISS))
                .thenReturn(snapshot(EPOCH, START))
                .thenThrow(new TleUnavailableException("CelesTrak injoignable"));

        store.get(ISS);
        clock.advance(MAX_AGE);

        assertThatExceptionOfType(TleTooOldException.class).isThrownBy(() -> store.get(ISS));
    }

    /**
     * CelesTrak demande explicitement de ne pas marteler son API. Dix requetes
     * simultanees sur le meme satellite doivent donner un appel, pas dix : c'est ce que
     * garantit l'atomicite par cle de {@code asMap().compute}.
     */
    @Test
    void collapsesConcurrentRequestsForTheSameSatelliteIntoOneCall() throws Exception {
        int callers = 10;
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);

        when(client.fetch(anyInt())).thenAnswer(invocation -> {
            fetches.incrementAndGet();
            Thread.sleep(50); // laisse le temps aux autres appelants d'arriver
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
