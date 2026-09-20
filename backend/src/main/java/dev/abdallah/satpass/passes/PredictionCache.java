package dev.abdallah.satpass.passes;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.TleSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Answers a request that has already been answered, instead of propagating the orbit
 * again.
 *
 * <h2>Why this exists</h2>
 * Every {@code /api/passes} call propagates over the whole window and samples it at 10 s.
 * That is milliseconds of CPU, but it is <em>linear</em>: a thousand calls for the same
 * satellite over the same city on the same day do the same work a thousand times. Margin
 * is decided before revenue, and a free tier that recomputes everything is the thing that
 * makes a free tier impossible.
 *
 * <h2>What invalidates an entry</h2>
 * <strong>A new TLE does</strong>, and that is the physically correct rule rather than an
 * arbitrary duration: the elements are the only input to the computation that changes on
 * its own. The entry records the two lines it was computed from; the caller has already
 * asked {@code TleStore} for the current ones, and a difference is a miss. A satellite
 * whose elements are republished every few hours therefore invalidates its own cache, and
 * one that is not re-observed for a day keeps serving — which is exactly what the physics
 * says should happen.
 *
 * <h2>And why a maximum age on top of it</h2>
 * The window starts at the instant of the request. An entry served later describes a
 * window that starts slightly in the past, so a pass that has just ended can still be
 * listed. The response has always carried {@code computedAt} for precisely this kind of
 * question, so the answer stays self-describing rather than becoming wrong — but the drift
 * has to be bounded, and {@code prediction-cache.max-age} is that bound.
 *
 * <h2>Why the observer is not rounded</h2>
 * The obvious economy is to quantise the site so that two observers in the same city share
 * an answer. It was rejected. Serving one caller a computation made for another position
 * means either echoing the observer they sent, while the passes belong to a different
 * point, or echoing a position they never sent. The first is a lie in a field this API
 * publishes; the second breaks the contract that a response describes the request.
 * The arithmetic makes the trade a poor one anyway: a 0.01° grid is about 1.1 km, some
 * 0.16° of elevation at typical low-orbit range — larger than the refraction this project
 * already declines to model — while the case that actually repeats, one integration
 * polling the same coordinates, hits the cache with exact keys regardless.
 *
 * <p>Keys therefore compare coordinates bit for bit, through {@code Double.hashCode} and
 * {@code equals} on a record. That makes {@code 45.7578} and {@code 45.75780000000001}
 * two different keys. They are two different requests; this cache does not decide they
 * are not.
 */
@Component
public class PredictionCache {

    /**
     * What a cached answer is keyed on: every input of the computation except the
     * elements, which are checked separately because they change without the caller.
     */
    record Key(int noradId,
               double latitudeDeg,
               double longitudeDeg,
               double altitudeMeters,
               long windowSeconds,
               double minElevationDeg) {

        static Key of(int noradId, ObserverLocation observer, Duration window, double minElevationDeg) {
            return new Key(noradId,
                    // +0.0 collapses the two zeros: -0.0 and 0.0 are the same meridian but
                    // not the same double, and Double.equals separates them.
                    observer.latitudeDeg() + 0.0,
                    observer.longitudeDeg() + 0.0,
                    observer.altitudeMeters() + 0.0,
                    window.toSeconds(),
                    minElevationDeg + 0.0);
        }
    }

    private final PredictionCacheProperties properties;
    private final Cache<Key, PassPrediction> cache;
    private final Counter hits;
    private final Counter misses;
    private final Timer computation;

    public PredictionCache(PredictionCacheProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                // Expiry is the coarse bound; freshness against the request's own instant
                // is checked on read, because the clock a test drives is not Caffeine's.
                .expireAfterWrite(properties.maxAge())
                .maximumSize(properties.maximumSize())
                .build();
        this.hits = Counter.builder("satpass.predictions")
                .tag("result", "hit")
                .description("Requests answered from an already computed prediction")
                .register(meters);
        this.misses = Counter.builder("satpass.predictions")
                .tag("result", "miss")
                .description("Requests that propagated the orbit")
                .register(meters);
        this.computation = Timer.builder("satpass.prediction.duration")
                .description("Time spent propagating and sampling, cache misses only")
                .publishPercentiles(0.5, 0.95)
                .register(meters);
    }

    /**
     * The answer for this request: the cached one when the elements and the age allow it,
     * otherwise {@code computation}'s, which is then stored.
     *
     * @param elements the snapshot the caller has just read from the store. It decides
     *                 validity, and it is passed in rather than read here so that the
     *                 cache never becomes a second place that can trigger a network call.
     */
    public PassPrediction get(int noradId,
                              ObserverLocation observer,
                              Duration window,
                              double minElevationDeg,
                              TleSnapshot elements,
                              Instant now,
                              Supplier<PassPrediction> computation) {
        if (!properties.enabled()) {
            return record(computation);
        }
        Key key = Key.of(noradId, observer, window, minElevationDeg);
        PassPrediction cached = cache.getIfPresent(key);
        if (cached != null && isUsable(cached, elements, now)) {
            hits.increment();
            return cached;
        }
        PassPrediction fresh = record(computation);
        cache.put(key, fresh);
        return fresh;
    }

    private PassPrediction record(Supplier<PassPrediction> supplier) {
        misses.increment();
        return computation.record(supplier);
    }

    private boolean isUsable(PassPrediction cached, TleSnapshot elements, Instant now) {
        return cached.tle().line1().equals(elements.line1())
                && cached.tle().line2().equals(elements.line2())
                && Duration.between(cached.computedAt(), now).compareTo(properties.maxAge()) < 0;
    }
}
