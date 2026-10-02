package space.nextpass;

import space.nextpass.validation.ValidationReference;
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

    /**
     * CelesTrak's answer to {@code gp.php?CATNR=100534&FORMAT=JSON}, captured on
     * 2 October 2026: a Starlink launched that week, with a six-digit number. The same
     * request in {@code FORMAT=TLE} answered 404 {@code No GP data found}.
     */
    public static final String STARLINK_100534_OMM = """
            [{"OBJECT_NAME":"STARLINK-38244","OBJECT_ID":"2026-200A","EPOCH":"2026-10-01T22:11:24.452448",\
            "MEAN_MOTION":15.7759124,"ECCENTRICITY":0.00032284,"INCLINATION":70.0006,"RA_OF_ASC_NODE":102.5417,\
            "ARG_OF_PERICENTER":274.4885,"MEAN_ANOMALY":85.5946,"EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U",\
            "NORAD_CAT_ID":100534,"ELEMENT_SET_NO":999,"REV_AT_EPOCH":469,"BSTAR":0.00066694375,\
            "MEAN_MOTION_DOT":0.00112611,"MEAN_MOTION_DDOT":0}]
            """;

    /**
     * The same element set as Space-Track writes it in {@code format/3le}: the number in
     * Alpha-5, {@code A0534} for 100534. Lines written by Orekit, checksums included.
     */
    public static final String STARLINK_100534_LINE1 =
            "1 A0534U 26200A   26274.92458857  .00112611  00000-0  66694-3 0  9997";
    public static final String STARLINK_100534_LINE2 =
            "2 A0534  70.0006 102.5417 0003228 274.4885  85.5946 15.77591240  4697";

    /**
     * One element set of the ISS in both of CelesTrak's forms, captured together on
     * 2 October 2026: {@code FORMAT=JSON} and {@code FORMAT=TLE}. What the OMM conversion
     * is measured against.
     */
    public static final String ISS_2026_OMM = """
            [{"OBJECT_NAME":"ISS (ZARYA)","OBJECT_ID":"1998-067A","EPOCH":"2026-10-02T00:19:52.567968",\
            "MEAN_MOTION":15.48707684,"ECCENTRICITY":0.00069463,"INCLINATION":51.6312,"RA_OF_ASC_NODE":131.4121,\
            "ARG_OF_PERICENTER":211.9293,"MEAN_ANOMALY":148.1275,"EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U",\
            "NORAD_CAT_ID":25544,"ELEMENT_SET_NO":999,"REV_AT_EPOCH":58831,"BSTAR":7.674284e-5,\
            "MEAN_MOTION_DOT":3.738e-5,"MEAN_MOTION_DDOT":0}]
            """;
    public static final String ISS_2026_LINE1 =
            "1 25544U 98067A   26275.01380287  .00003738  00000+0  76743-4 0  9992";
    public static final String ISS_2026_LINE2 =
            "2 25544  51.6312 131.4121 0006946 211.9293 148.1275 15.48707684588318";

    private TleFixtures() {
    }
}
