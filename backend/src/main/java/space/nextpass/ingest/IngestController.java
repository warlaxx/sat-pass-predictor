package space.nextpass.ingest;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import space.nextpass.gcat.GcatImportException;
import space.nextpass.gcat.GcatImporter;

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
 *
 * <p>Synchronous on purpose: the open request keeps Render from putting the service to
 * sleep mid-import, and the workflow's verdict is the import's.
 */
@Hidden
@RestController
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final byte[] token;
    private final ObjectProvider<GcatImporter> gcat;

    public IngestController(@Value("${ingest.token:}") String token, ObjectProvider<GcatImporter> gcat) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.gcat = gcat;
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

        GcatImporter importer = gcat.getIfAvailable();
        if (importer == null) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE,
                    "The import needs the database, and this instance has none (api-access.enabled).");
        }
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(importer.run());
        } catch (GcatImporter.AlreadyRunningException e) {
            return problem(HttpStatus.CONFLICT, e.getMessage());
        } catch (GcatImportException e) {
            log.warn("GCAT import failed", e);
            return problem(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
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
