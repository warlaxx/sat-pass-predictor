package dev.abdallah.satpass.tle;

import java.time.Duration;

/**
 * The only available TLE has an epoch too old for a prediction to mean anything.
 *
 * <p>Graceful degradation has a limit. SGP4 drifts by roughly a kilometre a day in low
 * Earth orbit; past a week, the along-track error amounts to minutes on the time of a
 * pass, and drawing a curve to the degree would be false precision. Better to say we do
 * not know.
 */
public final class TleTooOldException extends TleException {

    public TleTooOldException(int noradId, Duration age, Duration maxAge) {
        super("TLE for satellite " + noradId + " is " + age.toHours() + " h old,"
                + " beyond the " + maxAge.toHours() + " h limit");
    }
}
