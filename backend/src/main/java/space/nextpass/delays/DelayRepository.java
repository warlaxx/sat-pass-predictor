package space.nextpass.delays;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code spacetrack_debuts} and the {@code catalogue_delays} view of
 * {@code V7__catalogue_delays.sql}, which explains what the numbers mean and why they are
 * bounds.
 */
public class DelayRepository {

    /** first_fetched_at is written by the insert and never by the update. */
    private static final String UPSERT = """
            INSERT INTO spacetrack_debuts (norad_id, debut_at, intldes, name, first_fetched_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (norad_id) DO UPDATE
                SET debut_at = EXCLUDED.debut_at, intldes = EXCLUDED.intldes, name = EXCLUDED.name
            WHERE (spacetrack_debuts.debut_at, spacetrack_debuts.intldes, spacetrack_debuts.name)
                IS DISTINCT FROM (EXCLUDED.debut_at, EXCLUDED.intldes, EXCLUDED.name)""";

    private static final String STATS = """
            SELECT count(*) AS objects,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY gcat_after_spacetrack_hours) AS g50,
                   percentile_cont(0.9) WITHIN GROUP (ORDER BY gcat_after_spacetrack_hours) AS g90,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY spacetrack_after_separation_hours) AS s50,
                   percentile_cont(0.9) WITHIN GROUP (ORDER BY spacetrack_after_separation_hours) AS s90,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY gcat_after_separation_hours) AS c50,
                   percentile_cont(0.9) WITHIN GROUP (ORDER BY gcat_after_separation_hours) AS c90
            FROM catalogue_delays""";

    private final JdbcTemplate jdbc;

    public DelayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(List<SpaceTrackDebut> debuts, Instant runAt) {
        Timestamp now = Timestamp.from(runAt);
        jdbc.batchUpdate(UPSERT, debuts, 500, (ps, d) -> {
            ps.setInt(1, d.noradId());
            ps.setTimestamp(2, Timestamp.from(d.debutAt()));
            ps.setString(3, d.intldes());
            ps.setString(4, d.name());
            ps.setTimestamp(5, now);
        });
    }

    /** Debuts this run inserted. */
    public int countFirstFetched(Instant runAt) {
        return jdbc.queryForObject("SELECT count(*) FROM spacetrack_debuts WHERE first_fetched_at = ?",
                Integer.class, Timestamp.from(runAt));
    }

    /** Over separations only, or over every object both catalogues hold. */
    public DelayMeasurement.Summary summary(boolean separationsOnly) {
        return jdbc.queryForObject(STATS + (separationsOnly ? " WHERE is_separation" : ""),
                (rs, i) -> new DelayMeasurement.Summary(
                        rs.getInt("objects"),
                        new DelayMeasurement.Stat(hours(rs, "g50"), hours(rs, "g90")),
                        new DelayMeasurement.Stat(hours(rs, "s50"), hours(rs, "s90")),
                        new DelayMeasurement.Stat(hours(rs, "c50"), hours(rs, "c90"))));
    }

    /**
     * Objects Space-Track has catalogued since GCAT's first import and GCAT does not list
     * yet. They are the delays the summary cannot see — if this grows, GCAT's tail is
     * longer than the 90th percentile says.
     */
    public int countAwaitingGcat() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM spacetrack_debuts d
                WHERE d.debut_at > (SELECT min(first_seen_at) FROM gcat_objects)
                  AND NOT EXISTS (SELECT 1 FROM gcat_objects g WHERE g.satcat = d.norad_id)""",
                Integer.class);
    }

    /** Objects whose delay became measurable at or after {@code since}, at most {@code limit}. */
    public List<DelayMeasurement.Delay> measuredSince(Instant since, int limit) {
        return jdbc.query("""
                SELECT * FROM catalogue_delays WHERE measured_at >= ?
                ORDER BY is_separation DESC, spacetrack_debut_at DESC LIMIT ?""",
                (rs, i) -> new DelayMeasurement.Delay(
                        rs.getInt("norad_id"),
                        rs.getString("jcat"),
                        rs.getString("name"),
                        rs.getBoolean("is_separation"),
                        rs.getString("separation_text"),
                        rs.getTimestamp("spacetrack_debut_at").toInstant(),
                        rs.getTimestamp("gcat_first_seen_at").toInstant(),
                        hours(rs, "gcat_after_spacetrack_hours"),
                        hours(rs, "spacetrack_after_separation_hours"),
                        hours(rs, "gcat_after_separation_hours")),
                Timestamp.from(since), limit);
    }

    /** Hours to a tenth: the measure is a day wide at best, more digits would be noise. */
    private static Double hours(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
