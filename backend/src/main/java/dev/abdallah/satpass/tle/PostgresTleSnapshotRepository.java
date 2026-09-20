package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
import java.sql.Timestamp;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The persistent half of the TLE store, on the PostgreSQL that milestone 11 introduced.
 *
 * <h2>Failure is not propagated</h2>
 * Every call is wrapped. A database that is unreachable, slow or migrating must cost the
 * request nothing more than the in-memory behaviour it had before persistence existed —
 * the contract stated in {@link TleSnapshotRepository}. It is logged at {@code warn},
 * once per call, because a persistent store that has quietly stopped persisting is worth
 * noticing before the next restart discovers it.
 *
 * <h2>The query timeout is short on purpose</h2>
 * These calls happen while holding a key's lock inside {@code TleStore}, so their budget
 * is part of a request's latency. Two seconds is far more than a primary-key lookup on a
 * table with a few hundred rows needs, and far less than the pool's own connection
 * timeout — which is the failure this bound is really there to cut short.
 *
 * <h2>A write never goes backwards</h2>
 * Two instances can fetch the same satellite at nearly the same time. The {@code WHERE}
 * on the upsert keeps the more recent fetch whichever of the two commits last: without
 * it, a slow instance holding older elements would be able to overwrite newer ones, and
 * the stored age would move backwards for no reason a reader could explain.
 */
public class PostgresTleSnapshotRepository implements TleSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresTleSnapshotRepository.class);

    private static final String UPSERT = """
            INSERT INTO tle_snapshots (norad_id, name, line1, line2, epoch, fetched_at, source)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (norad_id) DO UPDATE SET
                name = EXCLUDED.name,
                line1 = EXCLUDED.line1,
                line2 = EXCLUDED.line2,
                epoch = EXCLUDED.epoch,
                fetched_at = EXCLUDED.fetched_at,
                source = EXCLUDED.source
            WHERE tle_snapshots.fetched_at < EXCLUDED.fetched_at""";

    private final JdbcTemplate jdbc;

    public PostgresTleSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.jdbc.setQueryTimeout(2);
    }

    @Override
    public Optional<TleSnapshot> find(int noradId) {
        try {
            return jdbc.query("""
                    SELECT norad_id, name, line1, line2, epoch, fetched_at, source
                    FROM tle_snapshots WHERE norad_id = ?""",
                    rs -> rs.next()
                            ? Optional.of(new TleSnapshot(
                                    rs.getInt("norad_id"),
                                    rs.getString("name"),
                                    rs.getString("line1"),
                                    rs.getString("line2"),
                                    rs.getTimestamp("epoch").toInstant(),
                                    rs.getTimestamp("fetched_at").toInstant(),
                                    rs.getString("source")))
                            : Optional.<TleSnapshot>empty(),
                    noradId);
        } catch (DataAccessException | IllegalArgumentException failure) {
            // IllegalArgumentException too: a row that no longer satisfies TleSnapshot's
            // invariants is a corrupt row, and refusing to start predicting because of one
            // is worse than fetching the elements again.
            log.warn("could not read the stored TLE for {}: {}", noradId, failure.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(TleSnapshot snapshot) {
        try {
            jdbc.update(UPSERT,
                    snapshot.noradId(),
                    snapshot.name(),
                    snapshot.line1(),
                    snapshot.line2(),
                    Timestamp.from(snapshot.epoch()),
                    Timestamp.from(snapshot.fetchedAt()),
                    snapshot.source());
        } catch (DataAccessException failure) {
            log.warn("could not store the TLE for {}: {}", snapshot.noradId(), failure.getMessage());
        }
    }

    @Override
    public void remove(int noradId) {
        try {
            jdbc.update("DELETE FROM tle_snapshots WHERE norad_id = ?", noradId);
        } catch (DataAccessException failure) {
            log.warn("could not forget the stored TLE for {}: {}", noradId, failure.getMessage());
        }
    }
}
