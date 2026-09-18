package dev.abdallah.satpass.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials and caps for Space-Track, the last source of the TLE chain.
 *
 * <h2>Optional on purpose</h2>
 * Space-Track needs an account, and a clone of this repository must build, test and run
 * without one. No identity, no password: the chain is simply built without that source,
 * and the application says so once at startup rather than failing at the first request.
 * A dependency that is optional has to be optional <em>at startup</em>, or it is not.
 *
 * <p>The credentials come from the environment, never from a committed file:
 * {@code SPACETRACK_IDENTITY} and {@code SPACETRACK_PASSWORD}. On Render they are marked
 * {@code sync: false} in {@code render.yaml}, which is how a Blueprint says "ask the
 * human, do not put this in git".
 *
 * @param baseUrl            root of the Space-Track API.
 * @param identity           the account's e-mail address. Blank disables the source.
 * @param password           the account's password. Blank disables the source.
 * @param requestsPerMinute  cap on calls, logins included, in any sliding minute.
 * @param requestsPerHour    the same over an hour. Both sit below Space-Track's published
 *                           limits of 30 and 300: a cap set exactly at the limit has no
 *                           room for the clock disagreeing, and the penalty for being
 *                           wrong is a suspended account.
 */
@ConfigurationProperties("tle.space-track")
public record SpaceTrackProperties(String baseUrl,
                                   String identity,
                                   String password,
                                   int requestsPerMinute,
                                   int requestsPerHour) {

    public SpaceTrackProperties {
        boolean hasCredentials = !isBlank(identity) && !isBlank(password);
        if (hasCredentials) {
            if (isBlank(baseUrl)) {
                throw new IllegalArgumentException(
                        "tle.space-track.base-url is missing while credentials are set");
            }
            if (requestsPerMinute <= 0 || requestsPerHour <= 0) {
                throw new IllegalArgumentException(
                        "tle.space-track.requests-per-minute and requests-per-hour must be"
                                + " strictly positive: an uncapped client is how an account"
                                + " gets suspended");
            }
            if (requestsPerMinute > requestsPerHour) {
                throw new IllegalArgumentException(
                        "tle.space-track.requests-per-minute must not exceed"
                                + " requests-per-hour, otherwise the hourly cap never bites");
            }
        } else if (!isBlank(identity) || !isBlank(password)) {
            // Half a credential is always a mistake, and it would otherwise disable the
            // source in silence - the exact shape of the defect this whole chain was
            // built after.
            throw new IllegalArgumentException(
                    "tle.space-track needs both identity and password, or neither");
        }
    }

    /** Whether this source can be built at all. */
    public boolean configured() {
        return !isBlank(identity) && !isBlank(password);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
