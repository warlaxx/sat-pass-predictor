package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.orekit.data.DataContext;
import org.orekit.errors.OrekitException;
import org.orekit.propagation.analytical.tle.TLE;

/**
 * Turns three lines of text into a validated {@link TleSnapshot}, or refuses them.
 *
 * <h2>Why this is shared and the clients are not</h2>
 * Two services, two ways of saying "I do not have this object": CelesTrak answers 200 with
 * {@code No GP data found}, Space-Track answers with an empty result. That belongs to each
 * client, and only that. What a valid TLE looks like belongs to NORAD, is the same
 * everywhere, and is the part worth getting right once.
 *
 * <h2>Validation, in this order</h2>
 * A corrupted response must fail at the edge of the system, where we still know why,
 * rather than deep inside a pass computation.
 * <ol>
 *   <li>69-character width and leading line number — the TLE format is column-based, a
 *       line of any other length is not a TLE.</li>
 *   <li>End-of-line checksum. Orekit exposes {@code TLE.isFormatOK} but does <em>not</em>
 *       verify it on construction: a digit altered in transit yields a perfectly accepted
 *       TLE, and wrong passes. So we recompute it here.</li>
 *   <li>Parsing by Orekit, which validates the fields themselves, then comparison of the
 *       returned NORAD number with the requested one. Without that last check, a response
 *       cached by an intermediary for another satellite would produce perfectly plausible
 *       passes — and wrong ones. With a relay in the chain, "an intermediary" stopped
 *       being hypothetical.</li>
 * </ol>
 *
 * <p>Everything it refuses is a {@link TleUnavailableException}: at this point the service
 * has answered <em>something</em>, so the only question left is whether the answer is
 * usable. "The catalogue does not hold this object" was decided before we got here.
 */
final class TleResponseParser {

    /** Length of a TLE line, fixed by NORAD's column-based format. */
    private static final int TLE_LINE_LENGTH = 69;

    private TleResponseParser() {
    }

    /**
     * @param endpoint base URL of the source, for messages — with several endpoints in
     *                 play, a failure that does not say which one is a failure to be
     *                 guessed at.
     * @param source   what goes into {@link TleSnapshot#source()}, and out to the client.
     * @throws TleUnavailableException if the body is not a usable TLE for {@code noradId}.
     */
    static TleSnapshot parse(int noradId,
                             String endpoint,
                             String source,
                             String body,
                             Instant fetchedAt,
                             DataContext dataContext) {
        List<String> lines = significantLines(body);
        if (lines.size() < 3) {
            throw new TleUnavailableException(
                    "unusable response from " + endpoint + " for satellite " + noradId
                            + ": " + lines.size() + " significant line(s), 3 expected");
        }

        String name = satelliteName(lines.get(0));
        String line1 = lines.get(1);
        String line2 = lines.get(2);
        requireWellFormed(noradId, line1, 1);
        requireWellFormed(noradId, line2, 2);

        TLE parsed = toOrekit(noradId, line1, line2);
        if (parsed.getSatelliteNumber() != noradId) {
            throw new TleUnavailableException(
                    endpoint + " returned satellite " + parsed.getSatelliteNumber()
                            + " when " + noradId + " was requested");
        }

        return new TleSnapshot(
                noradId,
                name,
                line1,
                line2,
                parsed.getDate().toInstant(dataContext.getTimeScales()),
                fetchedAt,
                source);
    }

    /**
     * The name line, without the {@code 0 } that the 3LE format puts in front of it.
     * CelesTrak's three-line output has no such prefix and Space-Track's 3LE does; the
     * satellite is the same one, and so should its name be. No catalogued object is named
     * starting with {@code "0 "}, which is what makes the test safe.
     */
    private static String satelliteName(String line) {
        String name = line.strip();
        if (name.startsWith("0 ") && !name.substring(2).isBlank()) {
            name = name.substring(2).strip();
        }
        return name;
    }

    /**
     * Width, line number and checksum.
     *
     * <p>The TLE checksum is the sum of the digits in the first 68 columns, minus signs
     * counting as 1 and everything else as 0, modulo 10. It occupies column 69. It
     * protects against alteration in transit, and it is worth nothing if nobody checks it
     * — which Orekit does not do on construction.
     */
    private static void requireWellFormed(int noradId, String line, int lineNumber) {
        if (line.length() != TLE_LINE_LENGTH) {
            throw new TleUnavailableException(
                    "line " + lineNumber + " of satellite " + noradId + ": expected "
                            + TLE_LINE_LENGTH + " characters, got " + line.length());
        }
        if (line.charAt(0) != (char) ('0' + lineNumber)) {
            throw new TleUnavailableException(
                    "line " + lineNumber + " of satellite " + noradId
                            + ": does not start with '" + lineNumber + "'");
        }
        int sum = 0;
        for (int i = 0; i < TLE_LINE_LENGTH - 1; i++) {
            char c = line.charAt(i);
            if (c >= '0' && c <= '9') {
                sum += c - '0';
            } else if (c == '-') {
                sum += 1;
            }
        }
        int expected = sum % 10;
        int actual = line.charAt(TLE_LINE_LENGTH - 1) - '0';
        if (actual != expected) {
            throw new TleUnavailableException(
                    "invalid checksum on line " + lineNumber + " of satellite " + noradId
                            + ": expected " + expected + ", read " + actual);
        }
    }

    /**
     * Orekit's TLE validates the fields themselves. Its {@link OrekitException} is
     * translated into an outage: a line corrupted in transit is indistinguishable, from
     * the outside, from a service returning nonsense.
     */
    private static TLE toOrekit(int noradId, String line1, String line2) {
        try {
            return new TLE(line1, line2);
        } catch (OrekitException | IllegalArgumentException e) {
            throw new TleUnavailableException(
                    "unreadable TLE for satellite " + noradId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Splits the response into non-empty lines. Both services pad the name line and end
     * with CRLF; only lines 1 and 2 keep their exact width of 69 characters, on which the
     * column-based format depends, so only trailing whitespace is removed.
     */
    private static List<String> significantLines(String body) {
        List<String> lines = new ArrayList<>(3);
        for (String raw : body.split("\\R")) {
            String line = raw.stripTrailing();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
