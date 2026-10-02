package space.nextpass.tle;

import space.nextpass.domain.TleSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.orekit.data.DataContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Fetches a TLE from CelesTrak's GP API.
 *
 * <h2>What CelesTrak actually returns</h2>
 * {@code gp.php?CATNR=25544&FORMAT=TLE} returns three lines of {@code text/plain}: the
 * satellite name, then lines 1 and 2. Two traps:
 * <ul>
 *   <li>An unknown NORAD number is announced by the <strong>body</strong>
 *       {@code No GP data found}, served either in 200 (documented by milestone 4) or in
 *       404 (observed from 30 September 2026). Trusting the HTTP status alone would, in
 *       the first form, let that string be parsed as a TLE and, in the second, report a
 *       re-entered satellite as an outage.</li>
 *   <li>Under abuse, CelesTrak answers with an HTML page, still in 200. Any body that
 *       does not look like a TLE is therefore treated as an outage, not as an answer.</li>
 * </ul>
 *
 * <h2>Absent is not the same as unusable</h2>
 * Only the {@code No GP data found} marker means "the catalogue does not have this
 * object". Everything else that is not a TLE — an empty body, a truncated response, an
 * HTML page — means "we did not get an answer", and is reported as
 * {@link TleUnavailableException}. The distinction is not cosmetic: the store treats
 * {@link TleNotFoundException} as permanent and drops the satellite, so mislabelling a
 * hiccup would throw away a perfectly good cached TLE and tell the user the satellite
 * does not exist.
 *
 * <h2>Validation at retrieval time</h2>
 * Width, checksum, Orekit parsing and the NORAD number are checked by
 * {@link TleResponseParser}, which every source shares: what a valid TLE looks like
 * belongs to NORAD, not to whoever served it. What stays here is what is CelesTrak's
 * own — how it says it does not have an object, and which format it serves an object in.
 *
 * <h2>No HTTP status alone means "unknown satellite"</h2>
 * A 404 counts as "not found" only when its body is the {@code No GP data found} marker;
 * any other error status, and a 404 with an HTML, empty or otherwise foreign body, is an
 * outage. The distinction earns its keep the moment this client is pointed at something
 * other than the origin: a relay whose route has been removed answers 404 too, and
 * reading that as "the catalogue does not have this satellite" would make
 * {@link TleStore} throw away a perfectly good cached TLE and tell the user the object
 * does not exist. A misrouted request is not a fact about the sky — only CelesTrak's own
 * sentence is.
 *
 * <h2>Six-digit catalogue numbers</h2>
 * Up to 99999 the request asks for {@code FORMAT=TLE}, whose lines are served verbatim.
 * Above it CelesTrak publishes no TLE at all: {@code FORMAT=TLE} answers
 * {@code No GP data found} for an object {@code FORMAT=JSON} returns (observed on
 * 2 October 2026), so those numbers are asked for in JSON and rewritten as Alpha-5 lines
 * by {@link OmmResponseParser}. The marker keeps its meaning in both formats, and so does
 * every check that follows.
 *
 * <h2>One endpoint per instance</h2>
 * This class talks to exactly one host. Trying more than one is
 * {@link FallbackTleClient}'s job, which is why the endpoint is carried here as a field:
 * with several instances in play, an error message that does not say <em>which</em> one
 * failed is a message that has to be guessed at.
 */
public class CelestrakTleClient implements TleClient {

    private static final Logger log = LoggerFactory.getLogger(CelestrakTleClient.class);

    /** What CelesTrak answers, in 200 or 404, for a NORAD number it does not hold. */
    private static final String NO_DATA_MARKER = "No GP data found";

    /**
     * How much of an error body is read to look for the marker. The marker is 16 bytes;
     * an HTML error page can be any size and only needs to be recognised as not-the-marker.
     */
    private static final int ERROR_BODY_PEEK = 256;

    private static final String SOURCE = "celestrak";

    /** The highest number CelesTrak's {@code FORMAT=TLE} serves: see the class javadoc. */
    private static final int MAX_TLE_FORMAT_NUMBER = 99_999;

    /** Base URL of this endpoint. Carried for messages and logs, nothing else. */
    private final String endpoint;

    private final RestClient restClient;
    private final DataContext dataContext;
    private final Clock clock;

    public CelestrakTleClient(String endpoint,
                              RestClient celestrakRestClient,
                              DataContext dataContext,
                              Clock clock) {
        this.endpoint = endpoint;
        this.restClient = celestrakRestClient;
        this.dataContext = dataContext;
        this.clock = clock;
    }

    /**
     * @throws TleNotFoundException    if the catalogue does not contain this number.
     * @throws TleUnavailableException if CelesTrak is unreachable or answers anything
     *                                 other than a usable TLE.
     */
    @Override
    public TleSnapshot fetch(int noradId) {
        String body = get(noradId);
        Instant fetchedAt = clock.instant();

        String trimmed = body == null ? "" : body.strip();
        if (isNoDataMarker(trimmed)) {
            throw new TleNotFoundException(noradId);
        }
        // An empty body is a failed exchange, not a statement about the catalogue.
        // Reporting it as "not found" would make the store forget a satellite it holds a
        // valid TLE for, on the strength of one truncated response.
        if (trimmed.isEmpty()) {
            throw new TleUnavailableException(
                    endpoint + " returned an empty body for satellite " + noradId);
        }

        String threeLines = hasTleFormat(noradId) ? trimmed
                : OmmResponseParser.toThreeLines(
                        noradId, endpoint, trimmed, dataContext.getTimeScales().getUTC());
        TleSnapshot snapshot = TleResponseParser.parse(
                noradId, endpoint, SOURCE, threeLines, fetchedAt, dataContext);
        log.info("fetched TLE for {} ({}) from {}, epoch {}",
                noradId, snapshot.name(), endpoint, snapshot.epoch());
        return snapshot;
    }

    private String get(int noradId) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/NORAD/elements/gp.php")
                            .queryParam("CATNR", noradId)
                            .queryParam("FORMAT", hasTleFormat(noradId) ? "TLE" : "JSON")
                            .build())
                    .retrieve()
                    // Every error status is an outage, except a 404 that carries the
                    // marker: see the class javadoc.
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        if (response.getStatusCode().value() == 404
                                && isNoDataMarker(peek(response.getBody()))) {
                            throw new TleNotFoundException(noradId);
                        }
                        throw new TleUnavailableException(
                                endpoint + " answered " + response.getStatusCode()
                                        + " for satellite " + noradId);
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            // Timeout, DNS, connection refused: the only useful information is that we
            // could not ask. It is passed up as-is so the store can degrade.
            throw new TleUnavailableException(
                    endpoint + " unreachable for satellite " + noradId, e);
        }
    }

    /** Whether CelesTrak publishes this number as TLE: five digits, no Alpha-5. */
    private static boolean hasTleFormat(int noradId) {
        return noradId <= MAX_TLE_FORMAT_NUMBER;
    }

    private static boolean isNoDataMarker(String body) {
        return body.strip().startsWith(NO_DATA_MARKER);
    }

    /** The start of an error body, or {@code ""} if it cannot be read. */
    private static String peek(InputStream body) {
        try (body) {
            return new String(body.readNBytes(ERROR_BODY_PEEK), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Unreadable is not the marker: the caller falls back to "outage".
            return "";
        }
    }
}
