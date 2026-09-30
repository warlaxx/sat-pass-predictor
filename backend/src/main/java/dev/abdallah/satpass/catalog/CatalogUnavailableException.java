package dev.abdallah.satpass.catalog;

/**
 * The name index could not be built: no source answered with a usable catalogue, and
 * nothing was held from an earlier download.
 *
 * <p>Not a {@link dev.abdallah.satpass.tle.TleException}: failing to look a name up says
 * nothing about a satellite's orbital elements, and a NORAD number typed in directly still
 * works. Keeping it out of that sealed hierarchy keeps it out of the pass endpoint's
 * error contract too.
 */
public final class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String message) {
        super(message);
    }

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
