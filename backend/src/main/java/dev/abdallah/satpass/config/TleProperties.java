package dev.abdallah.satpass.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for TLE retrieval.
 *
 * @param baseUrls       roots of the CelesTrak GP API, <strong>tried in order</strong>,
 *                       first usable answer wins. A list and not a single value because
 *                       reachability is not a property of the service alone: CelesTrak
 *                       answers some datacenters in 10 ms and silently drops the packets
 *                       of others, so the host the application is deployed on gets a vote
 *                       on whether the only configured source works. See
 *                       {@code FallbackTleClient}.
 * @param connectTimeout how long to wait for the connection to be established. With more
 *                       than one entry above, this is also the price of each unreachable
 *                       source before the next one is tried — it is a latency budget now,
 *                       not only a safety net.
 * @param readTimeout    how long to wait for the response body.
 * @param refreshAfter   past this age <em>since the last fetch</em>, the sources are
 *                       called again. This is not a validity period: if every call fails,
 *                       the previous snapshot keeps being served.
 * @param retryAfter     minimum delay between two <em>attempts</em>. Without it, an
 *                       outage turns every incoming request into a round of calls, since
 *                       a failed refresh leaves the snapshot — and therefore its age —
 *                       unchanged. Must not exceed {@code refreshAfter}, otherwise it
 *                       would also delay ordinary refreshes.
 * @param maxAge         past this age <em>since the epoch of the elements</em>, we refuse
 *                       to predict. This is the only hard limit, and it is about physics,
 *                       not about the network.
 * @param maximumSize    how many satellites are kept in memory. Bounds the store, which
 *                       otherwise never expires anything.
 */
@ConfigurationProperties("tle")
public record TleProperties(List<String> baseUrls,
                            Duration connectTimeout,
                            Duration readTimeout,
                            Duration refreshAfter,
                            Duration retryAfter,
                            Duration maxAge,
                            int maximumSize) {

    public TleProperties {
        if (baseUrls == null || baseUrls.isEmpty()) {
            throw new IllegalArgumentException("tle.base-urls must list at least one source");
        }
        if (baseUrls.stream().anyMatch(url -> url == null || url.isBlank())) {
            throw new IllegalArgumentException("tle.base-urls contains a blank entry");
        }
        baseUrls = List.copyOf(baseUrls);
        if (refreshAfter == null || refreshAfter.isNegative() || refreshAfter.isZero()) {
            throw new IllegalArgumentException("tle.refresh-after must be strictly positive");
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException("tle.retry-after must be strictly positive");
        }
        if (retryAfter.compareTo(refreshAfter) > 0) {
            throw new IllegalArgumentException(
                    "tle.retry-after must not exceed tle.refresh-after, otherwise the backoff"
                            + " would also hold back ordinary refreshes");
        }
        if (maxAge == null || maxAge.compareTo(refreshAfter) <= 0) {
            throw new IllegalArgumentException(
                    "tle.max-age must exceed tle.refresh-after, otherwise a TLE would be rejected"
                            + " before it ever had a chance to be refreshed");
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("tle.maximum-size must be strictly positive");
        }
    }
}
