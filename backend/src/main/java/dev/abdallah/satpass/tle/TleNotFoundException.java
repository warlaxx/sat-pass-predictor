package dev.abdallah.satpass.tle;

/**
 * CelesTrak does not know this NORAD number: a made-up number, or an object removed from
 * the catalogue because it re-entered the atmosphere.
 *
 * <p>A <em>permanent</em> error, unlike {@link TleUnavailableException}. That is what
 * justifies the store forgetting the satellite instead of going on serving its last known
 * TLE: predicting the passes of an object that has finished burning up would be worse
 * than a plain error.
 */
public class TleNotFoundException extends RuntimeException {

    private final int noradId;

    public TleNotFoundException(int noradId) {
        super("no TLE published for satellite " + noradId);
        this.noradId = noradId;
    }

    public int noradId() {
        return noradId;
    }
}
