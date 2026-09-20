package dev.abdallah.satpass.passes;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the prediction cache.
 *
 * @param enabled    whether identical requests reuse a computed answer. Off turns
 *                   {@link PredictionCache} into a pass-through, which is what the tests
 *                   that measure the propagation itself want.
 * @param maxAge     how long an entry may keep serving. This is <em>not</em> a validity
 *                   period for the physics — a new TLE evicts the entry whatever its age
 *                   — it bounds how far in the past the window start, and therefore the
 *                   {@code computedAt} the response carries, is allowed to be. Past it,
 *                   the answer is recomputed even though nothing about the orbit changed.
 * @param maximumSize how many answers are kept. Each one holds the sampled track of
 *                   every pass in its window, so this bounds memory, not just entries: a
 *                   168 h window over a low orbit is on the order of a hundred kilobytes.
 */
@ConfigurationProperties("prediction-cache")
public record PredictionCacheProperties(boolean enabled, Duration maxAge, int maximumSize) {

    public PredictionCacheProperties {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("prediction-cache.max-age must be strictly positive");
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("prediction-cache.maximum-size must be strictly positive");
        }
    }
}
