package dev.abdallah.satpass.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for TLE retrieval.
 *
 * @param baseUrl        root of the CelesTrak GP API.
 * @param connectTimeout how long to wait for the connection to be established.
 * @param readTimeout    how long to wait for the response body.
 * @param refreshAfter   past this age <em>since the last fetch</em>, CelesTrak is called
 *                       again. This is not a validity period: if the call fails, the
 *                       previous snapshot keeps being served.
 * @param retryAfter     minimum delay between two <em>attempts</em>. Without it, an
 *                       outage turns every incoming request into a CelesTrak call, since
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
public record TleProperties(String baseUrl,
                            Duration connectTimeout,
                            Duration readTimeout,
                            Duration refreshAfter,
                            Duration retryAfter,
                            Duration maxAge,
                            int maximumSize) {

    public TleProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("tle.base-url is missing");
        }
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
