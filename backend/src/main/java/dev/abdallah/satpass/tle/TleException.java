package dev.abdallah.satpass.tle;

/**
 * Everything that can go wrong while obtaining a TLE.
 *
 * <p>Sealed on purpose. The REST layer maps these to HTTP status codes in a single
 * {@code switch}; because the hierarchy is closed, adding a fourth case makes that switch
 * stop compiling instead of silently turning into a 500.
 */
public abstract sealed class TleException extends RuntimeException
        permits TleNotFoundException, TleUnavailableException, TleTooOldException {

    protected TleException(String message) {
        super(message);
    }

    protected TleException(String message, Throwable cause) {
        super(message, cause);
    }
}
