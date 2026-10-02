package space.nextpass.ingest;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point of the daily import, called by .github/workflows/daily-import.yml.
 *
 * <p>Why an endpoint rather than {@code @Scheduled}: on Render's free plan the service is
 * asleep most of the day, so an in-process scheduler would not fire reliably. GitHub
 * Actions wakes the service and calls this instead.
 *
 * <p>Fails closed: with no {@code ingest.token} configured the endpoint answers 503, so a
 * deployment that never set the secret exposes nothing. Not a {@code PassController}, so
 * the API-key interceptor does not apply, and hidden from the OpenAPI document.
 */
@Hidden
@RestController
public class IngestController {

    private final byte[] token;

    public IngestController(@Value("${ingest.token:}") String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/import")
    public ResponseEntity<?> runImport(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (token.length == 0) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "The import is not configured on this instance.");
        }
        if (!authorized(authorization)) {
            return problem(HttpStatus.UNAUTHORIZED, "Missing or invalid import token.");
        }

        Instant start = Instant.now();
        // TODO(phase 0): call the GCAT satcat import and the Space-Track SATCAT_DEBUT fetch,
        // and report what each one did. Until then this only proves the pipeline end to end.
        return ResponseEntity.ok(Map.of(
                "status", "noop",
                "gcatRows", 0,
                "newObjects", 0,
                "durationMs", Duration.between(start, Instant.now()).toMillis()));
    }

    private boolean authorized(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        // Constant time: the comparison reveals nothing about how much of the token matched.
        return MessageDigest.isEqual(token, provided);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle("Import");
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
