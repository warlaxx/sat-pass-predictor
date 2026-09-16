package dev.abdallah.satpass.api;

import dev.abdallah.satpass.tle.TleNotFoundException;
import dev.abdallah.satpass.tle.TleTooOldException;
import dev.abdallah.satpass.tle.TleUnavailableException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
 * here: {@code spring.mvc.problemdetails.enabled} already emits them in the same format.
 * Taking them over by hand would duplicate correct behaviour.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String TYPE_PREFIX = "https://github.com/warlaxx/sat-pass-predictor/errors/";

    /** Suggested delay before retrying, in seconds, when CelesTrak falters. */
    private static final String RETRY_AFTER_SECONDS = "300";

    @ExceptionHandler(TleNotFoundException.class)
    public ProblemDetail handleNotFound(TleNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "unknown-satellite"));
        problem.setTitle("Unknown satellite");
        problem.setProperty("noradId", e.noradId());
        return problem;
    }

    /**
     * 503 and not 502: the service cannot answer <em>for now</em>, and retrying makes
     * sense. It is also the only case where the store had nothing to degrade to — a
     * CelesTrak failure with a TLE in memory never reaches this far.
     */
    @ExceptionHandler(TleUnavailableException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> handleUnavailable(
            TleUnavailableException e) {
        log.warn("CelesTrak unavailable and no TLE in memory: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "No TLE available for this satellite: CelesTrak is unreachable and nothing"
                        + " has been fetched yet.");
        problem.setType(URI.create(TYPE_PREFIX + "tle-unavailable"));
        problem.setTitle("Orbital elements unavailable");
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }

    /**
     * A TLE exists, but its epoch is too old for the prediction to mean anything.
     * Answering 200 with a curve to the degree would be false precision; that is why this
     * case has its own {@code type} despite sharing a status code with the previous one.
     */
    @ExceptionHandler(TleTooOldException.class)
    public ProblemDetail handleTooOld(TleTooOldException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "tle-stale"));
        problem.setTitle("Orbital elements too old");
        return problem;
    }

    /**
     * Domain invariants — an unreachable elevation threshold, an impossible observer —
     * are errors in the request, not in the server.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "invalid-request"));
        problem.setTitle("Invalid request");
        return problem;
    }
}
