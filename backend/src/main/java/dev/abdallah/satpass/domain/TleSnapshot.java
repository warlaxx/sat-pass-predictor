package dev.abdallah.satpass.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * A TLE as it was retrieved, with what is needed to judge how fresh it is.
 *
 * <h2>Why the two raw lines and not an Orekit {@code TLE}</h2>
 * The domain imports no Orekit type (decided at milestone 3). The two lines are the
 * canonical form anyway: it is what CelesTrak publishes, what the API's JSON returns,
 * and what a user copies to check the result elsewhere. Orekit's {@code TLE} is rebuilt
 * on the fly in the propagation layer; parsing costs a few microseconds, negligible next
 * to a 24 h SGP4 propagation.
 *
 * <h2>Two ages, two uses</h2>
 * <ul>
 *   <li>{@link #ageSinceEpoch} — time elapsed since the epoch of the elements. This is
 *       the <em>physical</em> age: SGP4's error grows with it, on the order of a
 *       kilometre a day in low Earth orbit. It is what the interface's uncertainty
 *       banner shows.</li>
 *   <li>{@link #ageSinceFetch} — time elapsed since the network call. This is the
 *       <em>operational</em> age: it only decides whether to call CelesTrak again. A TLE
 *       fetched a minute ago may perfectly well have an epoch two days old, if the
 *       satellite has not been re-observed since.</li>
 * </ul>
 * Confusing the two is the classic mistake: a cache that expires after two hours believes
 * it guarantees an accuracy it does not control.
 */
public record TleSnapshot(int noradId,
                          String name,
                          String line1,
                          String line2,
                          Instant epoch,
                          Instant fetchedAt,
                          String source) {

    /** Length of a TLE line, fixed by NORAD's column-based format. */
    private static final int LINE_LENGTH = 69;

    public TleSnapshot {
        if (noradId <= 0) {
            throw new IllegalArgumentException("invalid NORAD number: " + noradId);
        }
        requireTleLine(line1, 1);
        requireTleLine(line2, 2);
        if (epoch == null) {
            throw new IllegalArgumentException("epoch is missing");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetch date is missing");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("satellite name is missing");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is missing");
        }
    }

    private static void requireTleLine(String line, int expectedNumber) {
        if (line == null || line.length() != LINE_LENGTH) {
            throw new IllegalArgumentException(
                    "line " + expectedNumber + ": expected " + LINE_LENGTH + " characters, got "
                            + (line == null ? "null" : line.length()));
        }
        if (line.charAt(0) != (char) ('0' + expectedNumber)) {
            throw new IllegalArgumentException(
                    "line " + expectedNumber + ": does not start with '" + expectedNumber + "'");
        }
    }

    /** Physical age of the elements: time elapsed since their epoch. May be negative. */
    public Duration ageSinceEpoch(Instant now) {
        return Duration.between(epoch, now);
    }

    /** Operational age: time elapsed since the network call that produced this snapshot. */
    public Duration ageSinceFetch(Instant now) {
        return Duration.between(fetchedAt, now);
    }
}
