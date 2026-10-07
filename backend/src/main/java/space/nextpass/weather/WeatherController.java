package space.nextpass.weather;

import io.swagger.v3.oas.annotations.Hidden;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cloud cover over a place, for the pass tables (ABD-36).
 *
 * <p>Not a {@code PassController}: it computes no pass, so neither the API key nor the
 * quota applies, and a forecast missing never stops a pass from showing. What protects
 * MET Norway is {@link CloudCoverService}'s cache and budget.
 *
 * <p>Hidden from the API documentation: it is the site's, not a contract.
 */
@Hidden
@RestController
public class WeatherController {

    /** Asked again in five minutes, not in a loop, when there is nothing to give. */
    static final String RETRY_AFTER_SECONDS = "300";

    private final CloudCoverService clouds;
    private final Clock clock;

    public WeatherController(CloudCoverService clouds, Clock clock) {
        this.clouds = clouds;
        this.clock = clock;
    }

    @GetMapping("/api/weather/clouds")
    public ResponseEntity<?> clouds(@RequestParam double lat, @RequestParam double lon) {
        if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "lat must be within [-90, 90] and lon within [-180, 180].");
            problem.setTitle("Invalid place");
            return ResponseEntity.badRequest().body(problem);
        }
        try {
            CloudCoverService.Result result = clouds.forecast(lat, lon);
            Duration keep = Duration.between(clock.instant(), result.expires());
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(keep.isNegative() ? Duration.ZERO : keep).cachePublic())
                    .body(result.forecast());
        } catch (WeatherUnavailableException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "No cloud forecast for this place right now.");
            problem.setTitle("Weather unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .cacheControl(CacheControl.noStore())
                    .body(problem);
        }
    }
}
