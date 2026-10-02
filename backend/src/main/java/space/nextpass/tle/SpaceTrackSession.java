package space.nextpass.tle;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

/**
 * One authenticated Space-Track account: its login, its cookie and its request budget.
 *
 * <h2>Why it is shared</h2>
 * Two callers query Space-Track: {@link SpaceTrackTleClient}, the last source of the TLE
 * chain, and the nightly catalogue-debut fetch of phase 3.2. Space-Track counts calls per
 * <em>account</em>, so they must spend one {@link RequestBudget}, and its rules ask that a
 * session cookie be reused rather than a login sent per query, so they share one cookie
 * too. A budget per caller would let the two together exceed the published limits while
 * each believed itself within them.
 *
 * <h2>The session</h2>
 * Space-Track authenticates with a form POST to {@code /ajaxauth/login} and a cookie. The
 * login happens once, lazily, and again only when a query comes back 401 or 403 — one
 * retry, never a loop. The cookie lives in the {@link java.net.CookieHandler} of the
 * {@link java.net.http.HttpClient} the {@code RestClient} is built on.
 *
 * <h2>Wrong credentials</h2>
 * Space-Track answers a bad login with <em>200</em> and {@code {"Login":"Failed"}}, so the
 * status alone would let a misconfiguration look like a working session. The body is
 * checked, and the failure is logged at ERROR: it is a permanent misconfiguration wearing
 * the costume of a transient outage.
 *
 * <p>Every failure — unreachable, refused, over budget, an error status — is a
 * {@link TleUnavailableException}: "this source cannot answer right now", which both
 * callers already know how to survive.
 */
public class SpaceTrackSession {

    private static final Logger log = LoggerFactory.getLogger(SpaceTrackSession.class);

    private final String endpoint;
    private final RestClient restClient;
    private final RequestBudget budget;
    private final String identity;
    private final String password;

    /** Whether a login has succeeded and its cookie is presumed still good. */
    private final AtomicBoolean authenticated = new AtomicBoolean(false);

    public SpaceTrackSession(String endpoint,
                             RestClient spaceTrackRestClient,
                             RequestBudget budget,
                             String identity,
                             String password) {
        this.endpoint = endpoint;
        this.restClient = spaceTrackRestClient;
        this.budget = budget;
        this.identity = identity;
        this.password = password;
    }

    public String endpoint() {
        return endpoint;
    }

    /**
     * GETs {@code path} (already URL-encoded) within the session, logging in first if
     * needed. {@code what} names the request in error messages ("satellite 25544").
     *
     * @throws TleUnavailableException if Space-Track is unreachable, refuses the
     *                                 credentials or the session twice, is over budget,
     *                                 or answers an error status.
     */
    public String get(String path, String what) {
        if (!authenticated.get()) {
            login(what);
        }
        try {
            return query(path, what);
        } catch (SessionExpired expired) {
            // The cookie was refused. One login, one retry, and then we stop: a loop here
            // would spend the whole budget proving the same point.
            authenticated.set(false);
            log.info("{} refused the session for {}, logging in again", endpoint, what);
            login(what);
            try {
                return query(path, what);
            } catch (SessionExpired stillExpired) {
                throw new TleUnavailableException(
                        endpoint + " refused the session twice for " + what);
            }
        }
    }

    private void login(String what) {
        requireBudget(what, "a login");
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

    private String query(String path, String what) {
        requireBudget(what, "a query");
        try {
            return restClient.get()
                    .uri(URI.create(endpoint + path))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)
                                || status.isSameCodeAs(HttpStatus.FORBIDDEN)) {
                            throw new SessionExpired();
                        }
                        throw new TleUnavailableException(
                                endpoint + " answered " + status + " for " + what);
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            throw new TleUnavailableException(endpoint + " unreachable for " + what, e);
        }
    }

    private void requireBudget(String what, String call) {
        if (!budget.tryAcquire()) {
            throw new TleUnavailableException(
                    endpoint + " request budget spent, refusing " + call + " for " + what
                            + ": see tle.space-track.requests-per-minute"
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
