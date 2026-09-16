package dev.abdallah.satpass.tle;

/**
 * CelesTrak could not be reached, or answered something unusable: timeout, network
 * failure, 5xx, an HTML error page instead of a TLE, an empty body.
 *
 * <p>A <em>transient</em> error: the store catches it and serves the last known TLE if it
 * has one. It only reaches the caller when there is nothing to degrade to.
 */
public final class TleUnavailableException extends TleException {

    public TleUnavailableException(String message) {
        super(message);
    }

    public TleUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
