package space.nextpass.delays;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3.2 of the roadmap (ABD-9): measure how late GCAT lists an object compared with
 * Space-Track's public catalogue, and how late both are after a separation.
 *
 * <p>Runs after the GCAT import, in the same nightly request. It never throws: Space-Track
 * unreachable, refusing the account or over budget becomes {@code "unavailable"} in the
 * report, and the GCAT import — already committed — stands. Without credentials it says
 * {@code "not-configured"} and still reports what earlier nights measured.
 */
public class DelayMeasurement {

    /** A median and a 90th percentile in hours, null while nothing is measured. */
    public record Stat(Double median, Double p90) {}

    /**
     * @param objects                  objects both catalogues hold, listed by GCAT after
     *                                 its first import
     * @param gcatAfterSpaceTrack      GCAT's first sighting minus Space-Track's debut
     * @param spaceTrackAfterSeparation Space-Track's debut minus the separation date, for
     *                                 dates known to the day or better
     * @param gcatAfterSeparation      GCAT's first sighting minus the separation date
     */
    public record Summary(int objects, Stat gcatAfterSpaceTrack, Stat spaceTrackAfterSeparation,
                          Stat gcatAfterSeparation) {}

    /** One object's delays, in hours; the separation ones are null without a day-precise date. */
    public record Delay(int noradId, String jcat, String name, boolean separation,
                        String separationText, Instant spaceTrackDebutAt, Instant gcatFirstSeenAt,
                        Double gcatAfterSpaceTrackHours, Double spaceTrackAfterSeparationHours,
                        Double gcatAfterSeparationHours) {}

    /**
     * @param status        {@code measured}, {@code unavailable} (Space-Track did not
     *                      answer; detail says why), {@code not-configured} or
     *                      {@code failed} (the database did not answer)
     * @param debutsFetched rows Space-Track returned for the last seven days
     * @param newDebuts     of which seen for the first time
     * @param newlyMeasured objects whose delay became measurable during this run
     */
    public record Report(String status, String detail, int debutsFetched, int newDebuts,
                         Summary separations, Summary allObjects, int awaitingGcat,
                         List<Delay> newlyMeasured) {

        public static Report failed(String detail) {
            return new Report("failed", detail, 0, 0, null, null, 0, List.of());
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DelayMeasurement.class);

    /** Enough to read in the workflow summary; the view holds the rest. */
    static final int REPORTED_OBJECTS = 50;

    private final SpaceTrackDebutSource source;
    private final DelayRepository repository;
    private final Clock clock;

    /** @param source null when Space-Track is not configured */
    public DelayMeasurement(SpaceTrackDebutSource source, DelayRepository repository, Clock clock) {
        this.source = source;
        this.repository = repository;
        this.clock = clock;
    }

    /** @param since the start of this nightly request, before the GCAT import */
    public Report run(Instant since) {
        String status;
        String detail = null;
        int fetched = 0;
        int inserted = 0;
        if (source == null) {
            status = "not-configured";
            detail = "SPACETRACK_IDENTITY / SPACETRACK_PASSWORD are not set";
        } else {
            // Truncated like GcatImporter's: PostgreSQL keeps microseconds, and
            // first_fetched_at = runAt is how the new rows are counted.
            Instant runAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            try {
                List<SpaceTrackDebut> debuts = source.recent();
                repository.upsert(debuts, runAt);
                fetched = debuts.size();
                inserted = repository.countFirstFetched(runAt);
                status = "measured";
                log.info("Space-Track debuts: {} over the last week, {} new", fetched, inserted);
            } catch (RuntimeException e) {
                // The GCAT import is already committed; this night only loses its debuts,
                // and the seven-day window will pick them up tomorrow.
                status = "unavailable";
                detail = e.getMessage();
                log.warn("Space-Track debuts unavailable, GCAT import kept: {}", e.getMessage());
            }
        }
        return new Report(status, detail, fetched, inserted,
                repository.summary(true), repository.summary(false),
                repository.countAwaitingGcat(),
                repository.measuredSince(since, REPORTED_OBJECTS));
    }
}
