package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a restart costs, once the elements are persisted.
 *
 * <p>A restart is modelled the way it actually happens: the same repository, a brand new
 * {@link TleStore}. Everything in memory is gone, the rows are not, and the question is
 * whether the first request still has to reach CelesTrak.
 */
class TleStorePersistenceTest {

    private static final int ISS = 25544;
    private static final Instant EPOCH = Instant.parse("2021-02-04T03:28:36.316Z");
    private static final Instant START = Instant.parse("2021-02-04T08:00:00Z");
    private static final Duration REFRESH_AFTER = Duration.ofHours(2);

    /** The database, minus the database: the contract is find/save/remove, nothing else. */
    private static final class InMemoryRepository implements TleSnapshotRepository {
        private final Map<Integer, TleSnapshot> rows = new HashMap<>();

        @Override public Optional<TleSnapshot> find(int noradId) {
            return Optional.ofNullable(rows.get(noradId));
        }
        @Override public void save(TleSnapshot snapshot) {
            rows.merge(snapshot.noradId(), snapshot,
                    (stored, fresh) -> fresh.fetchedAt().isAfter(stored.fetchedAt()) ? fresh : stored);
        }
        @Override public void remove(int noradId) {
            rows.remove(noradId);
        }
    }

    private TleClient client;
    private MutableClock clock;
    private InMemoryRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(TleClient.class);
        clock = new MutableClock(START);
        repository = new InMemoryRepository();
    }

    private TleStore newStore() {
        return new TleStore(client, properties(), repository, clock);
    }

    private static TleProperties properties() {
        return new TleProperties(List.of("https://celestrak.test"),
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMinutes(10),
                REFRESH_AFTER, Duration.ofMinutes(5), Duration.ofDays(7), 500);
    }

    private static TleSnapshot snapshot(Instant epoch, Instant fetchedAt) {
        return new TleSnapshot(ISS, TleFixtures.issName(), TleFixtures.issLine1(),
                TleFixtures.issLine2(), epoch, fetchedAt, "celestrak");
    }

    @Test
    void aRestartServesTheStoredElementsWithoutCallingUpstream() {
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));
        newStore().get(ISS);

        clock.advance(Duration.ofMinutes(30));
        TleSnapshot afterRestart = newStore().get(ISS);

        // The whole point of milestone 12: the first call after a restart is answered at
        // this service's availability, not CelesTrak's.
        verify(client, times(1)).fetch(ISS);
        assertThat(afterRestart.fetchedAt()).isEqualTo(START);
    }

    @Test
    void storedElementsPastTheRefreshWindowAreRenewedAtOnce() {
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));
        newStore().get(ISS);

        clock.advance(REFRESH_AFTER);
        Instant later = clock.instant();
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH.plus(REFRESH_AFTER), later));
        TleSnapshot afterRestart = newStore().get(ISS);

        // Loading a row is not an attempt to renew it: elements read back stale are due
        // for a refresh the moment they are read, restart or no restart.
        verify(client, times(2)).fetch(ISS);
        assertThat(afterRestart.fetchedAt()).isEqualTo(later);
        assertThat(repository.find(ISS)).contains(afterRestart);
    }

    @Test
    void staleStoredElementsStillServeWhenNoSourceAnswers() {
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));
        newStore().get(ISS);

        clock.advance(Duration.ofDays(2));
        when(client.fetch(ISS)).thenThrow(new TleUnavailableException("no source answered"));
        TleSnapshot served = newStore().get(ISS);

        assertThat(served.fetchedAt()).isEqualTo(START);
        assertThat(served.ageSinceEpoch(clock.instant())).isGreaterThan(Duration.ofDays(2));
    }

    @Test
    void anObjectThatLeftTheCatalogueDoesNotComeBackAtTheNextRestart() {
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));
        newStore().get(ISS);
        assertThat(repository.find(ISS)).isPresent();

        clock.advance(REFRESH_AFTER);
        when(client.fetch(ISS)).thenThrow(new TleNotFoundException(ISS));
        assertThatExceptionOfType(TleNotFoundException.class).isThrownBy(() -> newStore().get(ISS));

        // Re-entered, decayed, or simply withdrawn: propagating its last elements would
        // display the passes of a satellite that no longer exists.
        assertThat(repository.find(ISS)).isEmpty();
    }

    @Test
    void storedElementsPastTheAgeLimitAreStillRefusedRatherThanServed() {
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));
        newStore().get(ISS);

        clock.advance(Duration.ofDays(8));
        when(client.fetch(ISS)).thenThrow(new TleUnavailableException("no source answered"));

        // Persistence changes where the elements come from, never what is servable: the
        // seven-day limit is about physics and is applied when they are served.
        assertThatExceptionOfType(TleTooOldException.class).isThrownBy(() -> newStore().get(ISS));
    }

    @Test
    void aRepositoryThatFailsCostsNothingBeyondTheOldBehaviour() {
        TleSnapshotRepository broken = new TleSnapshotRepository() {
            @Override public Optional<TleSnapshot> find(int noradId) {
                throw new IllegalStateException("database down");
            }
            @Override public void save(TleSnapshot snapshot) {
                throw new IllegalStateException("database down");
            }
            @Override public void remove(int noradId) {
                throw new IllegalStateException("database down");
            }
        };
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));

        // Stated as an expectation, not a hope: an implementation is required to swallow
        // its own failures, and this is the test that says so out loud. What must not
        // happen is a prediction failing because a cache of convenience is unavailable.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new TleStore(client, properties(), broken, clock).get(ISS));
    }

    @Test
    void withoutARepositoryARestartFetchesAgain() {
        when(client.fetch(ISS)).thenReturn(snapshot(EPOCH, START));

        new TleStore(client, properties(), TleSnapshotRepository.NONE, clock).get(ISS);
        new TleStore(client, properties(), TleSnapshotRepository.NONE, clock).get(ISS);

        verify(client, times(2)).fetch(ISS);
        verify(client, never()).fetch(99999);
    }
}
