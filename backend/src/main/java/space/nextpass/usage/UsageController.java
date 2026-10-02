package space.nextpass.usage;

import io.swagger.v3.oas.annotations.Hidden;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Counts one action on the separation pages (ABD-8). Vercel's free plan records page
 * views but not custom events, so the clicks are counted here instead.
 *
 * <p>Nothing about the request is kept: not the address, not a header, not a time finer
 * than the day. The browser sends it as a beacon and ignores the answer, so every
 * outcome is 204 - an unknown action or an instance without a database records nothing.
 * Not part of the public API, hence hidden from its documentation.
 */
@Hidden
@RestController
public class UsageController {

    private static final Logger log = LoggerFactory.getLogger(UsageController.class);

    private final ObjectProvider<UsageRepository> repository;
    private final Clock clock;

    public UsageController(ObjectProvider<UsageRepository> repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @PostMapping("/api/usage/{event}")
    public ResponseEntity<Void> record(@PathVariable String event) {
        UsageRepository usage = repository.getIfAvailable();
        if (usage != null) {
            LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            try {
                UsageEvent.of(event).ifPresent(known -> usage.record(known, day));
            } catch (DataAccessException e) {
                // A lost count is not worth a failed page: the database may be waking up.
                log.warn("Usage count not recorded: {}", e.getMessage());
            }
        }
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
