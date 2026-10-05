package space.nextpass.ingest;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
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
import space.nextpass.delays.DelayMeasurement;
import space.nextpass.gcat.GcatImportException;
import space.nextpass.gcat.GcatImporter;
import space.nextpass.images.ImageImport;

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
 *
 * <p>After GCAT, the same request measures catalogue delays (phase 3.2,
 * {@link DelayMeasurement}). The report keeps GCAT's fields at the top level and adds
 * {@code delays}; whatever happens to the measurement, the GCAT import stands and the
 * answer stays 200.
 *
 * <p>Then the objects' photographs (ABD-45, {@link ImageImport}), under {@code images},
 * on the same terms: a failure there is reported, never fatal.
 */
@Hidden
@RestController
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    /** GCAT's report, unchanged at the top level, and the later steps beside it. */
    public record Report(@JsonUnwrapped GcatImporter.Report gcat, DelayMeasurement.Report delays,
                         ImageImport.Report images) {}

    private final byte[] token;
    private final ObjectProvider<GcatImporter> gcat;
    private final ObjectProvider<DelayMeasurement> delays;
    private final ObjectProvider<ImageImport> images;
    private final Clock clock;

    public IngestController(@Value("${ingest.token:}") String token,
                            ObjectProvider<GcatImporter> gcat,
                            ObjectProvider<DelayMeasurement> delays,
                            ObjectProvider<ImageImport> images,
                            Clock clock) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.gcat = gcat;
        this.delays = delays;
        this.images = images;
        this.clock = clock;
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
        DelayMeasurement measurement = delays.getIfAvailable();
        Instant started = measurement == null ? null : clock.instant();
        GcatImporter.Report report;
        try {
            report = importer.run();
        } catch (GcatImporter.AlreadyRunningException e) {
            return problem(HttpStatus.CONFLICT, e.getMessage());
        } catch (GcatImportException e) {
            log.warn("GCAT import failed", e);
            return problem(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Report(report, measure(measurement, started), images(images.getIfAvailable())));
    }

    private static DelayMeasurement.Report measure(DelayMeasurement measurement, Instant started) {
        if (measurement == null) {
            return null;
        }
        try {
            return measurement.run(started);
        } catch (RuntimeException e) {
            // Space-Track failures are already absorbed inside; this is the database.
            log.warn("Delay measurement failed, GCAT import kept", e);
            return DelayMeasurement.Report.failed(e.getMessage());
        }
    }

    private static ImageImport.Report images(ImageImport images) {
        if (images == null) {
            return null;
        }
        try {
            return images.run();
        } catch (RuntimeException e) {
            // Wikimedia's failures are already absorbed inside; this is the database.
            log.warn("Image import failed, GCAT import kept", e);
            return ImageImport.Report.failed(e.getMessage());
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
