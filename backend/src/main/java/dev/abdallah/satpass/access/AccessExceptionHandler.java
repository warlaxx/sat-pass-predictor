package dev.abdallah.satpass.access;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AccessExceptionHandler {
    private final Clock clock;
    public AccessExceptionHandler(Clock clock) { this.clock = clock; }

    @ExceptionHandler(AccessFailure.class)
    public ResponseEntity<ProblemDetail> handle(AccessFailure failure) {
        var problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()), failure.getMessage());
        problem.setType(URI.create("https://github.com/warlaxx/sat-pass-predictor/errors/" + failure.code()));
        problem.setTitle(switch (failure.status()) {
            case 401 -> "Invalid API key";
            case 429 -> "API request limit reached";
            default -> "API access unavailable";
        });
        var response = ResponseEntity.status(failure.status()).header("Cache-Control", "no-store");
        if (failure.resetsAt() != null) {
            problem.setProperty("limit", failure.code().equals("daily-quota-exceeded") ? "daily" : "minute");
            problem.setProperty("resetsAt", failure.resetsAt());
            response.header("Retry-After", Long.toString(Math.max(1,
                    (Duration.between(clock.instant(), failure.resetsAt()).toMillis() + 999) / 1000)));
        } else if (failure.status() == 401) {
            response.header("WWW-Authenticate", "ApiKey realm=\"sat-pass-predictor\"");
        } else if (failure.status() == 503) {
            response.header("Retry-After", "15");
        }
        return response.body(problem);
    }
}
