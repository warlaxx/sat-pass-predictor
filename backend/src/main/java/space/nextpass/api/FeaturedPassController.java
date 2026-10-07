package space.nextpass.api;

import space.nextpass.domain.ObserverLocation;
import space.nextpass.passes.PassQueryService;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The next passes of the ISS over Lyon, which the home page counts down to as it opens
 * (ABD-31).
 *
 * <p>Not a {@link PassController}, so neither the API key nor the quota applies. The home
 * page asks on every visit, and the public demo identity allows 200 predictions a day and
 * 20 a minute for the whole site: metering this call would spend that quota on visitors
 * who asked for nothing, and count them in {@code api_usage} as if they had. It takes no
 * parameter, so it cannot be used to compute anything else for free.
 *
 * <p>The answer is the one a default search computes: same satellite, observer, window
 * and threshold as {@code DEFAULT_QUERY} in the frontend, hence the same
 * {@code PredictionCache} entry. It is also kept here for {@link #REUSE_FOR}, so that the
 * cache's hit ratio - one of the two numbers a price is set from - is not inflated by one
 * hit per page view; and the browser is told it may keep it for as long.
 *
 * <p>{@code ?city=paris} asks the same for one of {@link FeaturedCities}, for the pages
 * {@code /iss/:city} (ABD-34): fifty fixed places, each kept the same way, so it computes
 * nothing a caller chooses. An unknown city is a 404.
 *
 * <p>Hidden from the API documentation: it is the home page's, not a contract.
 */
@Hidden
@RestController
public class FeaturedPassController {

    static final int NORAD_ID = 25544;
    static final ObserverLocation OBSERVER = new ObserverLocation(45.7578, 4.832, 170);
    static final Duration WINDOW = Duration.ofHours(48);
    static final double MIN_ELEVATION_DEG = 10;
    /** Far below the 48 h window: a minute-old answer still lists the next pass. */
    static final Duration REUSE_FOR = Duration.ofSeconds(60);

    private final PassQueryService passQueryService;
    private final Clock clock;
    private final AtomicReference<Kept> kept = new AtomicReference<>();
    private final Map<String, Kept> keptByCity = new ConcurrentHashMap<>();

    private record Kept(PassesResponse response, Instant at) {
    }

    public FeaturedPassController(PassQueryService passQueryService, Clock clock) {
        this.passQueryService = passQueryService;
        this.clock = clock;
    }

    @GetMapping("/api/featured-pass")
    public ResponseEntity<?> featured(@RequestParam(required = false) String city) {
        Instant now = clock.instant();
        if (city == null) {
            Kept current = kept.get();
            // Two requests racing past an expired entry both compute; the cache below makes
            // the second one a hit, which is cheaper than a lock held across a propagation.
            // A clock set back recomputes too: an answer from the future is not a fresh one.
            if (stale(current, now)) {
                current = compute(OBSERVER, now);
                kept.set(current);
            }
            return fresh(current);
        }
        var observer = FeaturedCities.observer(city);
        if (observer.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                    "No featured city is called \"" + city + "\".");
            problem.setTitle("Unknown city");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
        Kept current = keptByCity.get(city);
        if (stale(current, now)) {
            current = compute(observer.get(), now);
            keptByCity.put(city, current);
        }
        return fresh(current);
    }

    private static boolean stale(Kept kept, Instant now) {
        return kept == null || now.isBefore(kept.at()) || Duration.between(kept.at(), now).compareTo(REUSE_FOR) >= 0;
    }

    private Kept compute(ObserverLocation observer, Instant now) {
        return new Kept(PassesResponse.from(
                passQueryService.findPasses(NORAD_ID, observer, WINDOW, MIN_ELEVATION_DEG), null), now);
    }

    private static ResponseEntity<PassesResponse> fresh(Kept kept) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(REUSE_FOR).cachePublic())
                .body(kept.response());
    }
}
