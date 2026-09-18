package dev.abdallah.satpass.tle;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.abdallah.satpass.config.TleProperties;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single entry point to TLEs: the latest known elements per satellite, refreshed on
 * demand.
 *
 * <h2>Why this is not a TTL cache</h2>
 * The roadmap said "Caffeine cache, 2 h TTL", and two lines further down required that an
 * unreachable CelesTrak must not stop the application from working. The two do not hold
 * together: with a 2 h expiry, the first request arriving at 2 h 01 during a CelesTrak
 * outage finds nothing and can only return an error.
 *
 * <p>Hence the inversion: <strong>nothing expires</strong>. The two hours no longer
 * trigger an eviction but an <em>attempt</em> to refresh. If it succeeds, the snapshot is
 * replaced; if it fails, the old one is served with its real age, and it is the interface
 * that warns the user. A three-day-old TLE is still usable — less accurate, not wrong —
 * and that is precisely what this project sets out to show. Caffeine is therefore a
 * <em>bounded</em> store ({@code maximumSize}), not a cache.
 *
 * <h2>Two limits</h2>
 * <ul>
 *   <li>{@link TleTooOldException} past {@code tle.max-age}: degradation stops where
 *       prediction stops making sense.</li>
 *   <li>{@link TleNotFoundException} is never degraded, and <em>forgets</em> the
 *       satellite. An object that disappears from the catalogue has most likely
 *       re-entered; going on propagating its last TLE would display the passes of a
 *       satellite that no longer exists.</li>
 * </ul>
 *
 * <h2>Backing off after a failure</h2>
 * A failed refresh leaves the snapshot — and therefore its {@code fetchedAt} — unchanged,
 * so the "is it stale?" test stays true and the next request would call CelesTrak again.
 * Under traffic, an hour-long outage would mean one call per incoming request, which is
 * exactly what CelesTrak asks callers not to do. Each entry therefore remembers its last
 * <em>attempt</em>, not just its last success, and no new attempt is made before
 * {@code tle.retry-after} has elapsed.
 *
 * <h2>One network call per satellite</h2>
 * Refreshing happens inside {@code asMap().compute(...)}, which Caffeine makes atomic per
 * key: ten concurrent requests for the ISS produce one call to CelesTrak, not ten.
 * Accepted trade-off: a network call happens while holding the key's lock. It is bounded
 * by the client's timeouts (a few seconds), and only callers for the <em>same</em>
 * satellite wait.
 */
@Component
public class TleStore {

    private static final Logger log = LoggerFactory.getLogger(TleStore.class);

    private final TleClient client;
    private final TleProperties properties;
    private final Clock clock;
    private final Cache<Integer, Entry> store;

    public TleStore(TleClient client, TleProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.store = Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .build();
    }

    /**
     * What is held for one satellite: the elements, and when we last tried to renew them.
     *
     * <p>{@code lastAttempt} is not {@code snapshot.fetchedAt()}. They coincide after a
     * successful fetch and part company as soon as one fails — which is the whole point:
     * the age of the elements drives what we serve, the age of the attempt drives whether
     * we call out again.
     */
    private record Entry(TleSnapshot snapshot, Instant lastAttempt, TleUnavailableException failure) {
    }

    /**
     * The most recent TLE we have for this satellite.
     *
     * @throws TleNotFoundException    number absent from CelesTrak's catalogue.
     * @throws TleUnavailableException CelesTrak unreachable and no earlier TLE held.
     * @throws TleTooOldException      the only available TLE is too old to be of use.
     */
    public TleSnapshot get(int noradId) {
        Entry entry = store.asMap().compute(noradId, (id, existing) -> {
            Instant now = clock.instant();
            if (existing != null && !shouldAttemptRefresh(existing, now)) {
                return existing;
            }
            try {
                return new Entry(client.fetch(id), now, null);
            } catch (TleNotFoundException e) {
                return null; // Caffeine removes the entry: the satellite left the catalogue.
            } catch (TleUnavailableException e) {
                if (existing == null || existing.snapshot() == null) {
                    log.warn("Initial TLE fetch failed for {}: {}", id, e.getMessage(), e);
                    // Store the failed attempt too, so queued callers do not each retry.
                    return new Entry(null, clock.instant(), e);
                }
                log.warn("no TLE source answered for {} ({}) — keeping the TLE fetched at {}",
                        id, e.getMessage(), existing.snapshot().fetchedAt());
                // The attempt is recorded even though it failed; that is what stops the
                // next request from immediately calling CelesTrak again.
                return new Entry(existing.snapshot(), now, e);
            }
        });

        if (entry == null) {
            throw new TleNotFoundException(noradId);
        }
        if (entry.snapshot() == null) {
            throw new TleUnavailableException("No orbital elements fetched yet; retry in 15 seconds", entry.failure());
        }
        return checkAge(entry.snapshot());
    }

    /**
     * Two conditions, and both must hold: the elements are stale enough to be worth
     * renewing, and enough time has passed since the last attempt to be worth trying.
     */
    private boolean shouldAttemptRefresh(Entry entry, Instant now) {
        if (entry.snapshot() == null) {
            return !now.isBefore(entry.lastAttempt().plusSeconds(15));
        }
        boolean stale = entry.snapshot().ageSinceFetch(now).compareTo(properties.refreshAfter()) >= 0;
        if (!stale) {
            return false;
        }
        Duration sinceLastAttempt = Duration.between(entry.lastAttempt(), now);
        return sinceLastAttempt.compareTo(properties.retryAfter()) >= 0;
    }

    private TleSnapshot checkAge(TleSnapshot snapshot) {
        Duration age = snapshot.ageSinceEpoch(clock.instant());
        if (age.compareTo(properties.maxAge()) > 0) {
            throw new TleTooOldException(snapshot.noradId(), age, properties.maxAge());
        }
        return snapshot;
    }
}
