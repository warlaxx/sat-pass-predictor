package space.nextpass.tle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.orekit.errors.OrekitException;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns CelesTrak's OMM, in its JSON form, into the three lines {@link TleResponseParser}
 * validates — for the objects CelesTrak no longer publishes as TLE.
 *
 * <h2>Why this exists</h2>
 * Since July 2026 new objects carry six-digit catalogue numbers. CelesTrak's GP API
 * answers {@code FORMAT=TLE} for them with {@code No GP data found} — in 404, the very
 * marker that means "not in the catalogue" — while {@code FORMAT=JSON} returns their
 * elements (observed on 2 October 2026 for 100534). Asking for TLE would report a live
 * satellite as re-entered.
 *
 * <h2>Why lines, and not a second path to a prediction</h2>
 * The elements are rewritten as Alpha-5 TLE lines by Orekit, then go through the same
 * validation as any other source: width, checksum, Orekit parsing and, above all, the
 * comparison of the number with the one requested. The number written into the lines is
 * the one the response carries, never the one asked for, so a response for another
 * object is refused there rather than relabelled here.
 *
 * <h2>Why not Orekit's own OMM parser</h2>
 * Tried on CelesTrak's output on 2 October 2026: it refuses the XML form, and on the KVN
 * form {@code Omm.generateTLE()} halves {@code MEAN_MOTION_DOT}. CCSDS defines that field
 * as the derivative itself; CelesTrak publishes the TLE's field there, which already
 * holds half of it (ISS: {@code 3.738e-5} in the OMM, {@code .00003738} in the TLE of the
 * same element set). So the values are copied into the TLE's fields as they are, with
 * the factors Orekit's line parser applies to those same fields. SGP4 does not use either
 * derivative; the lines still say what CelesTrak says.
 */
final class OmmResponseParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code 2026-200A}: launch year, launch number of the year, piece. */
    private static final Pattern INTERNATIONAL_DESIGNATOR =
            Pattern.compile("(\\d{4})-(\\d{3})([A-Z]{1,3})");

    /** The width of the TLE fields: revolution number on 5 columns, element set on 4. */
    private static final int REVOLUTION_MODULO = 100_000;
    private static final int ELEMENT_SET_MODULO = 10_000;

    private OmmResponseParser() {
    }

    /**
     * @return the object's name, line 1 and line 2, separated by line breaks.
     * @throws TleUnavailableException if the body is not a usable OMM.
     */
    static String toThreeLines(int noradId, String endpoint, String body, TimeScale utc) {
        JsonNode omm = single(noradId, endpoint, body);
        try {
            TLE tle = new TLE(
                    integer(omm, "NORAD_CAT_ID"),
                    text(omm, "CLASSIFICATION_TYPE").charAt(0),
                    designator(omm, 1),
                    designator(omm, 2),
                    designatorPiece(omm),
                    integer(omm, "EPHEMERIS_TYPE"),
                    integer(omm, "ELEMENT_SET_NO") % ELEMENT_SET_MODULO,
                    new AbsoluteDate(text(omm, "EPOCH"), utc),
                    // rev/day, rev/day² and rev/day³ to SI: Orekit's own factors when it
                    // reads these fields from a line.
                    real(omm, "MEAN_MOTION") * Math.PI / 43200.0,
                    real(omm, "MEAN_MOTION_DOT") * Math.PI / 1.86624e9,
                    real(omm, "MEAN_MOTION_DDOT") * Math.PI / 5.3747712e13,
                    real(omm, "ECCENTRICITY"),
                    Math.toRadians(real(omm, "INCLINATION")),
                    Math.toRadians(real(omm, "ARG_OF_PERICENTER")),
                    Math.toRadians(real(omm, "RA_OF_ASC_NODE")),
                    Math.toRadians(real(omm, "MEAN_ANOMALY")),
                    // The TLE field wraps at 100000 revolutions; so does every TLE.
                    integer(omm, "REV_AT_EPOCH") % REVOLUTION_MODULO,
                    real(omm, "BSTAR"),
                    utc);
            return text(omm, "OBJECT_NAME") + "\n" + tle.getLine1() + "\n" + tle.getLine2();
        } catch (OrekitException | IllegalArgumentException e) {
            // Out-of-range elements, an unreadable epoch, a number beyond Alpha-5.
            throw unusable(noradId, endpoint, e.getMessage());
        }
    }

    /** CelesTrak answers {@code CATNR} with an array holding exactly one object. */
    private static JsonNode single(int noradId, String endpoint, String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            // An HTML page, a rate-limit notice, a truncated array: not an answer.
            throw unusable(noradId, endpoint, "not JSON");
        }
        if (root == null || !root.isArray() || root.size() != 1 || !root.get(0).isObject()) {
            throw unusable(noradId, endpoint, "expected an array of one object");
        }
        return root.get(0);
    }

    private static String text(JsonNode omm, String field) {
        JsonNode value = omm.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value.stringValue().strip();
    }

    private static double real(JsonNode omm, String field) {
        JsonNode value = omm.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(field + " is missing or not a number");
        }
        return value.doubleValue();
    }

    private static int integer(JsonNode omm, String field) {
        JsonNode value = omm.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " is missing or not an integer");
        }
        return value.intValue();
    }

    /**
     * Year (group 1) or launch number (group 2) of the international designator, or 0
     * when the object has none. Orekit has no blank for these columns; nothing reads them
     * back for a prediction, and the satellite search takes the launch from the OMM.
     */
    private static int designator(JsonNode omm, int group) {
        Matcher matcher = designatorOf(omm);
        return matcher == null ? 0 : Integer.parseInt(matcher.group(group));
    }

    private static String designatorPiece(JsonNode omm) {
        Matcher matcher = designatorOf(omm);
        return matcher == null ? "" : matcher.group(3);
    }

    private static Matcher designatorOf(JsonNode omm) {
        JsonNode value = omm.get("OBJECT_ID");
        if (value == null || !value.isString()) {
            return null;
        }
        Matcher matcher = INTERNATIONAL_DESIGNATOR.matcher(value.stringValue().strip());
        return matcher.matches() ? matcher : null;
    }

    private static TleUnavailableException unusable(int noradId, String endpoint, String why) {
        return new TleUnavailableException(
                "unusable OMM from " + endpoint + " for satellite " + noradId + ": " + why);
    }
}
