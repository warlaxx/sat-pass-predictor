package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.orekit.data.DataContext;
import org.orekit.errors.OrekitException;
import org.orekit.propagation.analytical.tle.TLE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Fetches a TLE from CelesTrak's GP API.
 *
 * <h2>What CelesTrak actually returns</h2>
 * {@code gp.php?CATNR=25544&FORMAT=TLE} returns three lines of {@code text/plain}: the
 * satellite name, then lines 1 and 2. Two traps:
 * <ul>
 *   <li>An unknown NORAD number does not give a 404 but a <strong>200 whose body is
 *       {@code No GP data found}</strong>. Trusting the HTTP status alone would let that
 *       string be parsed as a TLE.</li>
 *   <li>Under abuse, CelesTrak answers with an HTML page, still in 200. Any body that
 *       does not look like a TLE is therefore treated as an outage, not as an answer.</li>
 * </ul>
 *
 * <h2>Validation at retrieval time</h2>
 * A corrupted response must fail at the edge of the system, where we still know why,
 * rather than deep inside a pass computation. Three checks, in this order:
 * <ol>
 *   <li>69-character width and leading line number — the TLE format is column-based, a
 *       line of any other length is not a TLE.</li>
 *   <li>End-of-line checksum. Orekit exposes {@code TLE.isFormatOK} but does <em>not</em>
 *       verify it on construction: a digit altered in transit yields a perfectly accepted
 *       TLE, and wrong passes. So we recompute it here.</li>
 *   <li>Parsing by Orekit, which validates the fields themselves, then comparison of the
 *       returned NORAD number with the requested one.</li>
 * </ol>
 */
@Component
public class CelestrakTleClient {

    private static final Logger log = LoggerFactory.getLogger(CelestrakTleClient.class);

    /** What CelesTrak answers, in 200, for a NORAD number absent from the catalogue. */
    private static final String NO_DATA_MARKER = "No GP data found";

    private static final String SOURCE = "celestrak";

    /** Length of a TLE line, fixed by NORAD's column-based format. */
    private static final int TLE_LINE_LENGTH = 69;

    private final RestClient restClient;
    private final DataContext dataContext;
    private final Clock clock;

    public CelestrakTleClient(RestClient celestrakRestClient, DataContext dataContext, Clock clock) {
        this.restClient = celestrakRestClient;
        this.dataContext = dataContext;
        this.clock = clock;
    }

    /**
     * @throws TleNotFoundException    if the catalogue does not contain this number.
     * @throws TleUnavailableException if CelesTrak is unreachable or answers anything
     *                                 other than a usable TLE.
     */
    public TleSnapshot fetch(int noradId) {
        String body = get(noradId);
        Instant fetchedAt = clock.instant();

        String trimmed = body == null ? "" : body.strip();
        if (trimmed.isEmpty() || trimmed.startsWith(NO_DATA_MARKER)) {
            throw new TleNotFoundException(noradId);
        }

        List<String> lines = significantLines(body);
        if (lines.size() < 3) {
            throw new TleUnavailableException(
                    "unusable CelesTrak response for satellite " + noradId
                            + ": " + lines.size() + " significant line(s), 3 expected");
        }

        String name = lines.get(0).strip();
        String line1 = lines.get(1);
        String line2 = lines.get(2);
        requireWellFormed(noradId, line1, 1);
        requireWellFormed(noradId, line2, 2);

        TLE parsed = parse(noradId, line1, line2);
        if (parsed.getSatelliteNumber() != noradId) {
            throw new TleUnavailableException(
                    "CelesTrak returned satellite " + parsed.getSatelliteNumber()
                            + " when " + noradId + " was requested");
        }

        TleSnapshot snapshot = new TleSnapshot(
                noradId,
                name,
                line1,
                line2,
                parsed.getDate().toInstant(dataContext.getTimeScales()),
                fetchedAt,
                SOURCE);
        log.info("fetched TLE for {} ({}), epoch {}", noradId, name, snapshot.epoch());
        return snapshot;
    }

    private String get(int noradId) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/NORAD/elements/gp.php")
                            .queryParam("CATNR", noradId)
                            .queryParam("FORMAT", "TLE")
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            throw new TleNotFoundException(noradId);
                        }
                        throw new TleUnavailableException(
                                "CelesTrak answered " + response.getStatusCode()
                                        + " for satellite " + noradId);
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            // Timeout, DNS, connection refused: the only useful information is that we
            // could not ask. It is passed up as-is so the store can degrade.
            throw new TleUnavailableException(
                    "CelesTrak unreachable for satellite " + noradId, e);
        }
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
    private TLE parse(int noradId, String line1, String line2) {
        try {
            return new TLE(line1, line2);
        } catch (OrekitException | IllegalArgumentException e) {
            throw new TleUnavailableException(
                    "unreadable TLE for satellite " + noradId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Splits the response into non-empty lines. CelesTrak pads the name with spaces up to
     * 24 characters and ends with CRLF; only lines 1 and 2 keep their exact width of 69
     * characters, on which the column-based format depends.
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
