package space.nextpass.alerts;

import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reminders' endpoints (ABD-42): sign-up, confirmation and unsubscription for the
 * site, and the hourly round for {@code .github/workflows/pass-alerts.yml}.
 *
 * <p>Under {@code /api/} so that the site's own origin forwards them (vercel.json), but
 * not a {@code PassController}: no API key, no quota. Hidden from the API documentation,
 * like the weather and the usage counters - it is the site's, not a contract.
 *
 * <p>Without {@code alerts.enabled} every endpoint answers 503 and nothing is stored or
 * sent: the page then says reminders by e-mail are not open yet.
 */
@Hidden
@RestController
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);
    private static final String TYPE_PREFIX = "https://github.com/warlaxx/sat-pass-predictor/errors/";

    /** The sign-up as the page sends it; {@link AlertRequest} validates it. */
    public record Form(String email, Integer noradId, Double lat, Double lon, Integer minElevationDeg,
                       Integer maxCloudPercent, Double maxMagnitude, String timeZone, String locale) {}

    private final ObjectProvider<AlertService> alerts;
    private final byte[] token;

    public AlertController(ObjectProvider<AlertService> alerts, @Value("${ingest.token:}") String token) {
        this.alerts = alerts;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether this instance takes sign-ups: the page shows its form only then. */
    @GetMapping("/api/alerts")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(Map.of("enabled", alerts.getIfAvailable() != null));
    }

    /**
     * 202 whether the address is new, waiting or already confirmed: the answer must not
     * tell a stranger which addresses follow which satellite.
     */
    @PostMapping("/api/alerts")
    public ResponseEntity<?> subscribe(@RequestBody Form form) {
        AlertService service = alerts.getIfAvailable();
        if (service == null) {
            return unavailable();
        }
        AlertRequest request = new AlertRequest(form.email(), form.noradId(), form.lat(), form.lon(),
                form.minElevationDeg(), form.maxCloudPercent(), form.maxMagnitude(), form.timeZone(), form.locale());
        AlertService.SignupOutcome outcome;
        try {
            outcome = service.subscribe(request);
        } catch (Mailer.MailException e) {
            log.warn("Confirmation e-mail not sent: {}", e.getMessage());
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "alerts-unavailable", "Reminders unavailable",
                    "The confirmation e-mail could not be sent. Please try again later.");
        }
        return switch (outcome) {
            case PENDING -> ResponseEntity.accepted().header("Cache-Control", "no-store")
                    .body(Map.of("status", "pending"));
            case TOO_MANY -> problem(HttpStatus.CONFLICT, "alerts-limit", "Too many reminders",
                    "This address already has as many reminders as it may have. Unsubscribe from one first.");
            case BUSY -> problem(HttpStatus.SERVICE_UNAVAILABLE, "alerts-busy", "Too many sign-ups today",
                    "The day's confirmation e-mails are all spent. Please try again tomorrow.");
        };
    }

    @PostMapping("/api/alerts/confirm")
    public ResponseEntity<?> confirm(@RequestParam(required = false) String token) {
        AlertService service = alerts.getIfAvailable();
        if (service == null) {
            return unavailable();
        }
        return service.confirm(token)
                .<ResponseEntity<?>>map(alert -> ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of(
                        "noradId", alert.noradId(),
                        "lat", alert.latitudeDeg(),
                        "lon", alert.longitudeDeg(),
                        "minElevationDeg", alert.minElevationDeg(),
                        "maxCloudPercent", alert.maxCloudPercent())))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "alert-not-found", "Unknown or expired link",
                        "This link is unknown or has expired: sign-ups not confirmed within 48 hours are deleted."));
    }

    /**
     * The page's button and the one-click header of RFC 8058 (whose POST carries
     * {@code List-Unsubscribe=One-Click} as a form body, ignored here). 204 even for a
     * token already gone: the reader's wish is fulfilled either way.
     */
    @PostMapping("/api/alerts/unsubscribe")
    public ResponseEntity<?> unsubscribe(@RequestParam(required = false) String token) {
        AlertService service = alerts.getIfAvailable();
        if (service == null) {
            return unavailable();
        }
        service.unsubscribe(token);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    /** The hourly round. Same token as the nightly import: one secret for the internal jobs. */
    @PostMapping("/internal/alerts/send")
    public ResponseEntity<?> dispatch(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (token.length == 0) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "alerts-unavailable", "Reminders",
                    "The internal token is not configured on this instance.");
        }
        if (!authorized(authorization)) {
            return problem(HttpStatus.UNAUTHORIZED, "invalid-token", "Reminders", "Missing or invalid token.");
        }
        AlertService service = alerts.getIfAvailable();
        if (service == null) {
            return unavailable();
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.dispatch());
    }

    private boolean authorized(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, provided);
    }

    private static ResponseEntity<ProblemDetail> unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "alerts-unavailable", "Reminders unavailable",
                "E-mail reminders are not open on this instance.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create(TYPE_PREFIX + slug));
        body.setTitle(title);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
