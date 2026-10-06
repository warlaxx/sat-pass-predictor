package space.nextpass.images;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ABD-45: refresh the objects' photographs, after the GCAT import in the same nightly
 * request.
 *
 * <p>Wikimedia unreachable, rate-limiting or answering nonsense becomes
 * {@code "unavailable"} in the report and changes nothing: yesterday's images stand, and
 * tomorrow tries again. A list less than half as long as the one in the database is not
 * applied either ({@code "suspicious"}): a truncated SPARQL answer must not wipe the
 * pictures. Otherwise the list replaces the table in one transaction.
 */
public class ImageImport {

    /**
     * @param status  {@code imported}, {@code unavailable} or {@code suspicious}; a
     *                database failure is thrown, for the caller to report as {@code failed}
     * @param images  objects with a photograph after the run
     * @param removed images that disappeared since the previous run
     */
    public record Report(String status, String detail, int images, int removed, long durationMs) {

        public static Report failed(String detail) {
            return new Report("failed", detail, 0, 0, 0);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ImageImport.class);

    private final ImageSource source;
    private final ImageRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ImageImport(ImageSource source, ImageRepository repository, TransactionTemplate transaction, Clock clock) {
        this.source = source;
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
    }

    public Report run() {
        // Truncated like GcatImporter's: PostgreSQL keeps microseconds, and the rows this
        // run did not stamp are the ones to remove.
        Instant runAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<ObjectImage> images;
        try {
            images = source.fetch();
        } catch (ImageSourceException e) {
            log.warn("Images unavailable, previous ones kept: {}", e.getMessage());
            return new Report("unavailable", e.getMessage(), repository.count(), 0, elapsed(runAt));
        }
        int before = repository.count();
        if (images.size() * 2 < before) {
            String detail = "Wikimedia listed " + images.size() + " images against " + before + " stored";
            log.warn("Images not applied: {}", detail);
            return new Report("suspicious", detail, before, 0, elapsed(runAt));
        }
        int removed = transaction.execute(status -> {
            repository.upsert(images, runAt);
            return repository.removeOlderThan(runAt);
        });
        log.info("Images: {} objects, {} removed", images.size(), removed);
        return new Report("imported", null, images.size(), removed, elapsed(runAt));
    }

    private long elapsed(Instant runAt) {
        return Duration.between(runAt, clock.instant()).toMillis();
    }
}
