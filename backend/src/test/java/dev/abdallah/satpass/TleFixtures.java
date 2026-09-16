package dev.abdallah.satpass;

import dev.abdallah.satpass.validation.ValidationReference;
import org.orekit.propagation.analytical.tle.TLE;

/**
 * Frozen orbital elements for the tests.
 *
 * <p>A frozen TLE, never downloaded: a test that depends on the network is not a test, it
 * is a monitoring alert in disguise. It would fail the day CelesTrak is slow, and its
 * results would change on every run since TLEs are republished several times a day.
 */
public final class TleFixtures {

    /**
     * Read once. Every accessor used to reload and reparse the JSON resource, so building
     * one three-line response read it three times.
     */
    private static final ValidationReference REFERENCE = ValidationReference.load();

    /**
     * ISS (NORAD 25544), epoch 2021-02-04T03:28:36.316 UTC.
     *
     * <p>Read from {@code validation/iss-lyon-reference.json} rather than copied here:
     * the same TLE feeds the Java regression test and the Python validation script, and
     * two copies would end up diverging. The TLE comes from Orekit's own test data, hence
     * from a genuinely published TLE, with valid checksums.
     */
    public static TLE iss() {
        return REFERENCE.tle();
    }

    /** The name as CelesTrak publishes it for this satellite. */
    public static String issName() {
        return REFERENCE.satellite().name();
    }

    /** NORAD number of the ISS. */
    public static int issNoradId() {
        return REFERENCE.satellite().noradId();
    }

    /** Raw line 1, as it travels over the network. */
    public static String issLine1() {
        return REFERENCE.satellite().tleLine1();
    }

    /** Raw line 2. */
    public static String issLine2() {
        return REFERENCE.satellite().tleLine2();
    }

    /**
     * A CelesTrak response reconstructed exactly: name padded with spaces up to 24
     * characters, CRLF line endings, trailing empty line. Tests that do not reproduce
     * those details validate a format that does not exist.
     */
    public static String celestrakThreeLineResponse() {
        return String.format("%-24s", issName()) + "\r\n" + issLine1() + "\r\n" + issLine2() + "\r\n";
    }

    private TleFixtures() {
    }
}
