package space.nextpass.gcat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 3.0 of the roadmap: bring GCAT's satellite catalogues into the database.
 *
 * <h2>Two files, not one</h2>
 * {@code satcat.tsv} stops at catalogue number 69999. Objects catalogued since July 2026
 * have six-digit numbers and live in {@code satcat100k.tsv}; reading only the first file
 * would silently freeze the catalogue at 11 July 2026. The list comes from
 * {@code gcat.files}.
 *
 * <h2>One transaction per file</h2>
 * A file is applied whole or not at all, together with its validators: a download cut
 * halfway leaves the previous night's rows and the previous ETag, so the next run
 * downloads it again instead of believing it current. Upserting is idempotent, so a
 * rerun of a file that did complete changes nothing.
 *
 * <h2>One run at a time</h2>
 * A second call while one runs is refused ({@link AlreadyRunningException}) rather than
 * queued: the workflow serialises its own runs, and anything else calling twice is a
 * mistake worth an error, not twice the work.
 */
public class GcatImporter {

    public record FileReport(String file, String status, int rows, int unparsedCells) {}

    public record Report(
            String status,
            List<FileReport> files,
            int rowsRead,
            int newObjects,
            int updatedObjects,
            int newSeparations,
            // Rows whose is_separation the run changed: the new separations among them, and
            // every old row a change of the rule or of a parent moved.
            int reclassifiedSeparations,
            long durationMs) {}

    public static class AlreadyRunningException extends RuntimeException {
        public AlreadyRunningException() {
            super("A GCAT import is already running");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GcatImporter.class);

    /** Rows per round trip: large enough to amortise latency, small enough for 512 MB. */
    static final int BATCH_SIZE = 500;

    private final GcatSource source;
    private final List<String> files;
    private final GcatRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    public GcatImporter(GcatSource source, List<String> files, GcatRepository repository,
                        TransactionTemplate transaction, Clock clock) {
        this.source = source;
        this.files = List.copyOf(files);
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
    }

    public Report run() {
        if (!running.tryLock()) {
            throw new AlreadyRunningException();
        }
        try {
            // PostgreSQL keeps microseconds: the same truncation on both sides is what lets
            // first_seen_at = runAt find the rows this run inserted.
            Instant runAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            List<FileReport> reports = new ArrayList<>();
            int written = 0;
            for (String file : files) {
                int[] fileWritten = {0};
                FileReport report = transaction.execute(status -> importFile(file, runAt, fileWritten));
                written += fileWritten[0];
                reports.add(report);
                log.info("GCAT {}: {} ({} rows, {} written, {} unparsed cells)",
                        file, report.status(), report.rows(), fileWritten[0], report.unparsedCells());
            }
            // After both files: a row's parent may be in the other one.
            int reclassified = transaction.execute(status -> repository.reclassify(runAt));
            int inserted = repository.countFirstSeen(runAt);
            boolean anyImported = reports.stream().anyMatch(r -> r.status().equals("imported"));
            return new Report(
                    anyImported ? "imported" : "unchanged",
                    reports,
                    reports.stream().mapToInt(FileReport::rows).sum(),
                    inserted,
                    written - inserted,
                    repository.countNewSeparations(runAt),
                    reclassified,
                    Duration.between(runAt, clock.instant()).toMillis());
        } finally {
            running.unlock();
        }
    }

    private FileReport importFile(String file, Instant runAt, int[] written) {
        List<GcatObject> batch = new ArrayList<>(BATCH_SIZE);
        GcatParser.Result[] parsed = {null};
        var validators = source.fetch(file, repository.validators(file), reader ->
                parsed[0] = GcatParser.parse(reader, object -> {
                    batch.add(object);
                    if (batch.size() == BATCH_SIZE) {
                        written[0] += repository.upsert(batch, runAt);
                        batch.clear();
                    }
                }));
        if (validators.isEmpty()) {
            return new FileReport(file, "unchanged", 0, 0);
        }
        if (!batch.isEmpty()) {
            written[0] += repository.upsert(batch, runAt);
        }
        repository.saveFile(file, validators.get(), parsed[0].rows(), runAt);
        return new FileReport(file, "imported", parsed[0].rows(), parsed[0].unparsed());
    }
}
