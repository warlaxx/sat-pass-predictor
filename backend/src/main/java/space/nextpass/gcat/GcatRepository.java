package space.nextpass.gcat;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The GCAT tables of {@code V5__gcat_objects.sql}.
 *
 * <h2>An unchanged row is not written</h2>
 * The nightly import re-reads 70 000 rows of which a handful changed. The upsert's
 * {@code IS DISTINCT FROM} guard skips the others, so a quiet night writes almost nothing
 * — which matters on a free Neon compute, and makes {@link #upsert}'s count mean "rows
 * that changed" rather than "rows that were read".
 *
 * <h2>first_seen_at is set once</h2>
 * The insert writes it, the update never touches it: it records the first import that had
 * the row, which phase 3.2 compares with the event's own date to measure GCAT's delay.
 */
public class GcatRepository {

    private static final String COLUMNS = """
            satcat, launch_tag, piece, type, name, payload_name,
            launch_text, launch_at, launch_precision, parent, parent_text,
            separation_text, separation_at, separation_precision, separation_uncertain,
            primary_body, decay_text, decay_at, status, owner, state,
            mass_kg, perigee_km, apogee_km, inclination_deg, op_orbit, alt_names, is_separation""";

    private static final String UPSERT = """
            INSERT INTO gcat_objects (jcat, %1$s, first_seen_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (jcat) DO UPDATE SET (%1$s, updated_at) = (%2$s, EXCLUDED.updated_at)
            WHERE (%3$s) IS DISTINCT FROM (%2$s)""".formatted(
                    COLUMNS,
                    prefixed("EXCLUDED."),
                    prefixed("gcat_objects."));

    private final JdbcTemplate jdbc;

    public GcatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts new rows, updates changed ones, and returns how many it wrote. */
    public int upsert(List<GcatObject> objects, Instant runAt) {
        Timestamp now = Timestamp.from(runAt);
        int[][] counts = jdbc.batchUpdate(UPSERT, objects, objects.size(), (ps, o) -> bind(ps, o, now));
        return Arrays.stream(counts).flatMapToInt(Arrays::stream).filter(n -> n > 0).sum();
    }

    public GcatSource.Validators validators(String file) {
        return jdbc.query("SELECT etag, last_modified FROM gcat_files WHERE name = ?",
                rs -> rs.next()
                        ? new GcatSource.Validators(rs.getString("etag"), rs.getString("last_modified"))
                        : GcatSource.Validators.NONE,
                file);
    }

    public void saveFile(String file, GcatSource.Validators validators, int rows, Instant importedAt) {
        jdbc.update("""
                INSERT INTO gcat_files (name, etag, last_modified, rows_read, imported_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET etag = EXCLUDED.etag,
                    last_modified = EXCLUDED.last_modified, rows_read = EXCLUDED.rows_read,
                    imported_at = EXCLUDED.imported_at""",
                file, validators.etag(), validators.lastModified(), rows, Timestamp.from(importedAt));
    }

    /** Rows this run inserted. */
    public int countFirstSeen(Instant runAt) {
        return jdbc.queryForObject("SELECT count(*) FROM gcat_objects WHERE first_seen_at = ?",
                Integer.class, Timestamp.from(runAt));
    }

    /** Separations this run inserted. */
    public int countNewSeparations(Instant runAt) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM gcat_objects WHERE first_seen_at = ? AND is_separation",
                Integer.class, Timestamp.from(runAt));
    }

    private static String prefixed(String prefix) {
        return Arrays.stream(COLUMNS.split(","))
                .map(column -> prefix + column.strip())
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow();
    }

    private static void bind(PreparedStatement ps, GcatObject o, Timestamp now) throws SQLException {
        int i = 1;
        ps.setString(i++, o.jcat());
        ps.setObject(i++, o.satcat(), Types.INTEGER);
        ps.setString(i++, o.launchTag());
        ps.setString(i++, o.piece());
        ps.setString(i++, o.type());
        ps.setString(i++, o.name());
        ps.setString(i++, o.payloadName());
        ps.setString(i++, o.launchText());
        ps.setTimestamp(i++, at(o.launch()));
        ps.setString(i++, precision(o.launch()));
        ps.setString(i++, o.parent());
        ps.setString(i++, o.parentText());
        ps.setString(i++, o.separationText());
        ps.setTimestamp(i++, at(o.separation()));
        ps.setString(i++, precision(o.separation()));
        ps.setObject(i++, o.separation() == null ? null : o.separation().uncertain(), Types.BOOLEAN);
        ps.setString(i++, o.primaryBody());
        ps.setString(i++, o.decayText());
        ps.setTimestamp(i++, at(o.decay()));
        ps.setString(i++, o.status());
        ps.setString(i++, o.owner());
        ps.setString(i++, o.state());
        ps.setObject(i++, o.massKg(), Types.DOUBLE);
        ps.setObject(i++, o.perigeeKm(), Types.DOUBLE);
        ps.setObject(i++, o.apogeeKm(), Types.DOUBLE);
        ps.setObject(i++, o.inclinationDeg(), Types.DOUBLE);
        ps.setString(i++, o.opOrbit());
        ps.setString(i++, o.altNames());
        ps.setBoolean(i++, o.isSeparation());
        ps.setTimestamp(i++, now);
        ps.setTimestamp(i, now);
    }

    private static Timestamp at(GcatDate date) {
        return date == null ? null : Timestamp.from(date.start());
    }

    private static String precision(GcatDate date) {
        return date == null ? null : date.precision().name();
    }
}
