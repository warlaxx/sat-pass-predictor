package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.orekit.data.DataContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

/**
 * Fetches a TLE from Space-Track, the catalogue CelesTrak republishes.
 *
 * <h2>What it is for, and what it is not for</h2>
 * It is an <strong>availability</strong> fallback, not a <strong>coverage</strong> one. It
 * sits last in the chain, behind CelesTrak and behind the relay, and it answers the
 * question "we could not reach the others" — not "the others do not have this object".
 * That distinction is what lets {@link FallbackTleClient} keep stopping on a
 * {@code not found}: CelesTrak is the mirror of the same public catalogue, so its "I do
 * not have it" is as authoritative as Space-Track's would be.
 *
 * <p>It is also what protects the account. {@code /api/passes} is public and its NORAD
 * number is a query parameter; without that rule, a stream of made-up numbers would turn
 * into one authenticated call each — and negative answers are not cached, since the store
 * removes the entry. The chain stops before it gets here.
 *
 * <h2>The session</h2>
 * Space-Track authenticates with a form POST to {@code /ajaxauth/login} and a cookie, and
 * its API rules ask that the cookie be reused rather than a login sent per query. So the
 * login happens once, lazily, and again only when a query comes back 401 or 403 — one
 * retry, never a loop. The cookie lives in the {@link java.net.CookieHandler} of the
 * {@link java.net.http.HttpClient} this client's {@code RestClient} is built on, which is
 * why that client is its own and not shared with the CelesTrak endpoints.
 *
 * <h2>Two ways this differs from CelesTrak, both handled here</h2>
 * <ul>
 *   <li>An object the catalogue does not hold comes back as an <strong>empty
 *       result</strong>, not as a marker in a 200 body. Empty therefore means
 *       {@link TleNotFoundException} here, where it means "failed exchange" for
 *       CelesTrak — the same bytes, opposite meanings, which is exactly why each client
 *       owns that decision and only the parsing is shared.</li>
 *   <li>{@code format/3le} prefixes the name line with {@code 0 }.
 *       {@link TleResponseParser} strips it so that one satellite has one name whichever
 *       source answered.</li>
 * </ul>
 *
 * <h2>Wrong credentials</h2>
 * Space-Track answers a bad login with <em>200</em> and {@code {"Login":"Failed"}}, so the
 * status alone would let a misconfiguration look like a working session. The body is
 * checked, and the failure is logged at ERROR: it is a permanent misconfiguration wearing
 * the costume of a transient outage, and the only thing that keeps it from being retried
 * forever is the store's backoff and the {@link RequestBudget}.
 */
public class SpaceTrackTleClient implements TleClient {

    private static final Logger log = LoggerFactory.getLogger(SpaceTrackTleClient.class);

    private static final String SOURCE = "space-track";

    /**
     * The {@code gp} class holds the current element set, one row per object
     * ({@code gp_history} is the historical one). {@code limit/1} costs nothing and says
     * out loud that exactly one TLE is expected.
     */
    private static final String QUERY =
            "/basicspacedata/query/class/gp/NORAD_CAT_ID/%d/limit/1/format/3le";

    private final String endpoint;
    private final RestClient restClient;
    private final DataContext dataContext;
    private final Clock clock;
    private final RequestBudget budget;
    private final String identity;
    private final String password;

    /** Whether a login has succeeded and its cookie is presumed still good. */
    private final AtomicBoolean authenticated = new AtomicBoolean(false);

    public SpaceTrackTleClient(String endpoint,
                               RestClient spaceTrackRestClient,
                               DataContext dataContext,
                               Clock clock,
                               RequestBudget budget,
                               String identity,
                               String password) {
        this.endpoint = endpoint;
        this.restClient = spaceTrackRestClient;
        this.dataContext = dataContext;
        this.clock = clock;
        this.budget = budget;
        this.identity = identity;
        this.password = password;
    }

    /**
     * @throws TleNotFoundException    if the catalogue does not contain this number.
     * @throws TleUnavailableException if Space-Track is unreachable, refuses the
     *                                 credentials, is over budget, or answers anything
     *                                 other than a usable TLE.
     */
    @Override
    public TleSnapshot fetch(int noradId) {
        String body = query(noradId);
        Instant fetchedAt = clock.instant();

        String trimmed = body == null ? "" : body.strip();
        // Space-Track says "no such object" by returning nothing. Unlike CelesTrak, where
        // an empty body is a truncated exchange, here it is the answer.
        if (trimmed.isEmpty() || "[]".equals(trimmed)) {
            throw new TleNotFoundException(noradId);
        }

        TleSnapshot snapshot = TleResponseParser.parse(
                noradId, endpoint, SOURCE, trimmed, fetchedAt, dataContext);
        log.info("fetched TLE for {} ({}) from {}, epoch {}",
                noradId, snapshot.name(), endpoint, snapshot.epoch());
        return snapshot;
    }

    private String query(int noradId) {
        if (!authenticated.get()) {
            login(noradId);
        }
        try {
            return get(noradId);
        } catch (SessionExpired expired) {
            // The cookie was refused. One login, one retry, and then we stop: a loop here
            // would spend the whole budget proving the same point.
            authenticated.set(false);
            log.info("{} refused the session for satellite {}, logging in again",
                    endpoint, noradId);
            login(noradId);
            try {
                return get(noradId);
            } catch (SessionExpired stillExpired) {
                throw new TleUnavailableException(
                        endpoint + " refused the session twice for satellite " + noradId);
            }
        }
    }

    private void login(int noradId) {
        requireBudget(noradId, "a login");
        String body;
        try {
            body = restClient.post()
                    .uri(URI.create(endpoint + "/ajaxauth/login"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new TleUnavailableException(
                                endpoint + " answered " + response.getStatusCode()
                                        + " to the login");
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            throw new TleUnavailableException(endpoint + " unreachable at login", e);
        }

        // 200 is not success here: a refused login comes back 200 with {"Login":"Failed"}.
        if (body != null && body.toLowerCase(Locale.ROOT).contains("fail")) {
            log.error("{} rejected the Space-Track credentials — check"
                    + " tle.space-track.identity and SPACETRACK_PASSWORD", endpoint);
            throw new TleUnavailableException(endpoint + " rejected the credentials");
        }
        authenticated.set(true);
    }

    private String form() {
        return "identity=" + UriUtils.encode(identity, StandardCharsets.UTF_8)
                + "&password=" + UriUtils.encode(password, StandardCharsets.UTF_8);
    }

    private String get(int noradId) {
        requireBudget(noradId, "a query");
        try {
            return restClient.get()
                    .uri(URI.create(endpoint + String.format(QUERY, noradId)))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)
                                || status.isSameCodeAs(HttpStatus.FORBIDDEN)) {
                            throw new SessionExpired();
                        }
                        throw new TleUnavailableException(
                                endpoint + " answered " + status + " for satellite " + noradId);
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            throw new TleUnavailableException(
                    endpoint + " unreachable for satellite " + noradId, e);
        }
    }

    private void requireBudget(int noradId, String what) {
        if (!budget.tryAcquire()) {
            throw new TleUnavailableException(
                    endpoint + " request budget spent, refusing " + what + " for satellite "
                            + noradId + ": see tle.space-track.requests-per-minute"
                            + " and requests-per-hour");
        }
    }

    /** Internal signal: the cookie was refused, and a fresh login is worth one try. */
    private static final class SessionExpired extends RuntimeException {
        SessionExpired() {
            super(null, null, false, false);
        }
    }
}
