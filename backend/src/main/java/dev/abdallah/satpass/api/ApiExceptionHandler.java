package dev.abdallah.satpass.api;

import dev.abdallah.satpass.tle.TleException;
import dev.abdallah.satpass.tle.TleNotFoundException;
import dev.abdallah.satpass.tle.TleTooOldException;
import dev.abdallah.satpass.tle.TleUnavailableException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain failures into HTTP responses, in the Problem Details format
 * (RFC 9457).
 *
 * <p>Each case has its own {@code type}, a stable URI: that is what lets a client tell
 * "this satellite does not exist" from "CelesTrak is down" without reading a human
 * message. The HTTP status alone would not do — two very different causes share 503 here.
 *
 * <p>Framework exceptions (missing parameter, out of bounds, wrong type) are not handled
 * here: {@code spring.mvc.problemdetails.enabled}, set in {@code application.yml},
 * already emits them in the same format. Taking them over by hand would duplicate
 * correct behaviour.
 *
 * <p>The TLE failures go through <b>one</b> handler and an exhaustive {@code switch} over
 * the sealed {@link TleException}. Three separate {@code @ExceptionHandler} methods would
 * work today and turn a fourth subtype into a silent 500 tomorrow; here the compiler
 * refuses the fourth subtype until this switch names it. That is the whole point of
 * having sealed the hierarchy.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String TYPE_PREFIX = "https://github.com/warlaxx/sat-pass-predictor/errors/";

    /** Suggested delay before retrying, in seconds, when the TLE sources falter. */
    private static final String RETRY_AFTER_SECONDS = "300";

    @ExceptionHandler(TleException.class)
    public ResponseEntity<ProblemDetail> handleTleFailure(TleException e) {
        return switch (e) {
            case TleNotFoundException notFound -> {
                ProblemDetail problem = problem(HttpStatus.NOT_FOUND, notFound.getMessage(),
                        "unknown-satellite", "Unknown satellite");
                problem.setProperty("noradId", notFound.noradId());
                yield ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
            }
            // 503 and not 502: the service cannot answer *for now*, and retrying makes
            // sense. It is also the only case where the store had nothing to degrade
            // to — a source failure with a TLE in memory never reaches this far.
            case TleUnavailableException unavailable -> {
                // The exception goes in as the last argument, not just its message. The
                // cause IS the diagnosis: "unreachable" covers a connect timeout, a read
                // timeout, a DNS failure and a refused connection, and those four are
                // fixed in four different places. Logging getMessage() alone turned a
                // named defect into a guess - it cost a deploy cycle to find out.
                // Suppressed exceptions come with it: with a chain of sources, one
                // message is no longer the diagnosis - the origin timing out and the
                // relay answering 502 are two repairs in two different places.
                log.warn("no TLE source answered and nothing in memory: {}",
                        unavailable.getMessage(), unavailable);
                ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "No TLE available for this satellite: no source of orbital elements"
                                + " could be reached, and nothing has been fetched yet.",
                        "tle-unavailable", "Orbital elements unavailable");
                yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                        .body(problem);
            }
            // A TLE exists, but its epoch is too old for the prediction to mean anything.
            // Answering 200 with a curve to the degree would be false precision; that is
            // why this case has its own type despite sharing a status code with the
            // previous one.
            case TleTooOldException tooOld -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(problem(HttpStatus.SERVICE_UNAVAILABLE, tooOld.getMessage(),
                            "tle-stale", "Orbital elements too old"));
        };
    }

    /**
     * Domain invariants — an unreachable elevation threshold, an impossible observer —
     * are errors in the request, not in the server.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid-request", "Invalid request");
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String slug, String title) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + slug));
        problem.setTitle(title);
        return problem;
    }
}
