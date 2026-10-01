package space.nextpass.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the satellite name index. Its sources are {@code tle.base-urls}: the index
 * is served by the same CelesTrak endpoints, and has the same reachability problem.
 *
 * @param refreshAfter past this age, the next search downloads the index again. Names
 *                     change rarely; CelesTrak asks that a group not be downloaded more
 *                     often than it is republished, every two hours.
 * @param retryAfter   minimum delay between two download attempts, so that an outage is
 *                     not turned into one download per keystroke.
 */
@ConfigurationProperties("catalog")
public record CatalogProperties(Duration refreshAfter, Duration retryAfter) {

    public CatalogProperties {
        if (refreshAfter == null || refreshAfter.compareTo(Duration.ofHours(2)) < 0) {
            throw new IllegalArgumentException(
                    "catalog.refresh-after must be at least 2h, CelesTrak's republication interval");
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException("catalog.retry-after must be strictly positive");
        }
        if (retryAfter.compareTo(refreshAfter) > 0) {
            throw new IllegalArgumentException("catalog.retry-after must not exceed catalog.refresh-after");
        }
    }
}
