package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
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
 *   <li>An unknown NORAD number does not give a 404 but a <strong>200 whose body is
 *       {@code No GP data found}</strong>. Trusting the HTTP status alone would let that
 *       string be parsed as a TLE.</li>
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
 * belongs to NORAD, not to whoever served it. What stays here is the one thing that is
 * CelesTrak's own — how it says it does not have an object.
 *
 * <h2>No HTTP status means "unknown satellite"</h2>
 * The GP API never answers 404 for an object it does not hold — it answers 200 with
 * {@code No GP data found}. So <strong>every</strong> error status, 404 included, is an
 * outage here. The distinction earns its keep the moment this client is pointed at
 * something other than the origin: a relay whose route has been removed answers 404, and
 * reading that as "the catalogue does not have this satellite" would make
 * {@link TleStore} throw away a perfectly good cached TLE and tell the user the object
 * does not exist. A misrouted request is not a fact about the sky.
 *
 * <h2>One endpoint per instance</h2>
 * This class talks to exactly one host. Trying more than one is
 * {@link FallbackTleClient}'s job, which is why the endpoint is carried here as a field:
 * with several instances in play, an error message that does not say <em>which</em> one
 * failed is a message that has to be guessed at.
 */
public class CelestrakTleClient implements TleClient {

    private static final Logger log = LoggerFactory.getLogger(CelestrakTleClient.class);

    /** What CelesTrak answers, in 200, for a NORAD number absent from the catalogue. */
    private static final String NO_DATA_MARKER = "No GP data found";

    private static final String SOURCE = "celestrak";

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
        if (trimmed.startsWith(NO_DATA_MARKER)) {
            throw new TleNotFoundException(noradId);
        }
        // An empty body is a failed exchange, not a statement about the catalogue.
        // Reporting it as "not found" would make the store forget a satellite it holds a
        // valid TLE for, on the strength of one truncated response.
        if (trimmed.isEmpty()) {
            throw new TleUnavailableException(
                    endpoint + " returned an empty body for satellite " + noradId);
        }

        TleSnapshot snapshot = TleResponseParser.parse(
                noradId, endpoint, SOURCE, trimmed, fetchedAt, dataContext);
        log.info("fetched TLE for {} ({}) from {}, epoch {}",
                noradId, snapshot.name(), endpoint, snapshot.epoch());
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
                    // Every error status, 404 included: see the class javadoc. The
                    // only answer that means "unknown satellite" is the body marker.
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
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
}
