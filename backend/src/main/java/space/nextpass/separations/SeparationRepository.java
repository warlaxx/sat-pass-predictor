package space.nextpass.separations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import space.nextpass.separations.Separations.Date;
import space.nextpass.separations.Separations.Event;
import space.nextpass.separations.Separations.Evidence;
import space.nextpass.separations.Separations.Image;
import space.nextpass.separations.Separations.Kind;
import space.nextpass.separations.Separations.Month;
import space.nextpass.separations.Separations.Orbit;
import space.nextpass.separations.Separations.SpaceObject;
import space.nextpass.separations.Separations.Stats;
import space.nextpass.separations.Separations.Summary;

/**
 * Reads separation events out of {@code gcat_objects} (see {@code V5__gcat_objects.sql}).
 *
 * <h2>What an event is</h2>
 * The rows that share a parent and a recorded separation date. Its address is the
 * smallest record identifier among them: stable while the group does not change, and
 * any other member's identifier still finds the same event, so a link never breaks.
 *
 * <h2>What is left out</h2>
 * Records the catalogue itself marks as errors ({@code ERR}, duplicates for the most
 * part). An object is in orbit while it has no decay date: that is what the record says,
 * whatever its status code means in detail.
 */
public class SeparationRepository {

    /** The children shown on an event page; a breakup can leave hundreds. */
    static final int MAX_CHILDREN = 200;

    private static final String VISIBLE = " o.is_separation AND o.separation_at IS NOT NULL"
            + " AND o.status IS DISTINCT FROM 'ERR'";

    private static final String EVENTS = """
            SELECT o.parent, o.separation_text, min(o.jcat) AS id, count(*) AS children,
                   count(*) FILTER (WHERE o.type LIKE 'D%') AS debris,
                   bool_or(o.decay_text IS NULL) AS in_orbit,
                   min(o.perigee_km) AS perigee, max(o.apogee_km) AS apogee,
                   min(o.inclination_deg) AS inclination, min(o.op_orbit) AS orbit_class,
                   min(o.separation_at) AS separation_at, min(o.separation_precision) AS precision,
                   bool_or(o.separation_uncertain) AS uncertain
            FROM gcat_objects o
            WHERE """ + VISIBLE + "\n" + """
            GROUP BY o.parent, o.separation_text""";

    private static final String COLUMNS = """
            jcat, satcat, name, payload_name, piece, type, owner, state, mass_kg,
            launch_text, launch_at, launch_precision, perigee_km, apogee_km, inclination_deg,
            op_orbit, decay_text, parent_text, separation_text, status""";

    /** The object's photograph, when it has one (ABD-45, {@code V8__object_images.sql}). */
    private static final String IMAGE = """
            , i.thumb_url, i.thumb_width, i.thumb_height, i.author, i.licence, i.licence_url,
            i.description_url""";

    private static final String WITH_IMAGE = " FROM gcat_objects o LEFT JOIN object_images i ON i.norad_id = o.satcat";

    private final JdbcTemplate jdbc;

    public SeparationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest events first, of one kind or both. */
    public List<Summary> latest(Kind kind, int limit) {
        String filter = kind == null ? "" : kind == Kind.FRAGMENTATION
                ? "WHERE ev.debris = ev.children" : "WHERE ev.debris < ev.children";
        return jdbc.query("WITH ev AS (" + EVENTS + ")" + """
                SELECT ev.*, f.name AS first_name, f.satcat AS first_satcat,
                       p.name AS parent_name, p.owner AS parent_owner, p.state AS parent_state
                FROM ev JOIN gcat_objects f ON f.jcat = ev.id
                LEFT JOIN gcat_objects p ON p.jcat = ev.parent
                """ + filter + "\n" + """
                ORDER BY ev.separation_at DESC, ev.id
                LIMIT ?""",
                (rs, row) -> new Summary(
                        rs.getString("id"),
                        rs.getInt("debris") == rs.getInt("children") ? Kind.FRAGMENTATION : Kind.RELEASE,
                        new Date(rs.getString("separation_text"), instant(rs, "separation_at"),
                                rs.getString("precision"), rs.getBoolean("uncertain")),
                        rs.getString("parent_name"),
                        rs.getString("parent_owner"),
                        rs.getString("parent_state"),
                        rs.getString("first_name"),
                        integer(rs, "first_satcat"),
                        rs.getInt("children"),
                        new Orbit(number(rs, "perigee"), finite(number(rs, "apogee")),
                                number(rs, "inclination"), rs.getString("orbit_class")),
                        rs.getBoolean("in_orbit")),
                limit);
    }

    /** Totals and this year's events by month, for the chart above the list. */
    public Stats stats(Instant now) {
        int year = now.atZone(ZoneOffset.UTC).getYear();
        Timestamp start = Timestamp.from(Year.of(year).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        int[] totals = jdbc.queryForObject(
                "SELECT count(*) AS objects, count(*) FILTER (WHERE o.separation_at >= ?) AS this_year"
                        + " FROM gcat_objects o WHERE " + VISIBLE,
                (rs, row) -> new int[] {rs.getInt("objects"), rs.getInt("this_year")}, start);
        List<Month> counted = jdbc.query("WITH ev AS (" + EVENTS + ")" + """
                SELECT to_char(ev.separation_at AT TIME ZONE 'UTC', 'YYYY-MM') AS month,
                       count(*) FILTER (WHERE ev.debris < ev.children) AS releases,
                       count(*) FILTER (WHERE ev.debris = ev.children) AS fragmentations
                FROM ev WHERE ev.separation_at >= ?
                GROUP BY 1 ORDER BY 1""",
                (rs, row) -> new Month(rs.getString("month"), rs.getInt("releases"), rs.getInt("fragmentations")),
                start);
        // Every month up to the current one, so that a month without events reads as zero.
        int months = now.atZone(ZoneOffset.UTC).getMonthValue();
        List<Month> byMonth = new ArrayList<>();
        for (int m = 1; m <= months; m++) {
            String key = "%d-%02d".formatted(year, m);
            byMonth.add(counted.stream().filter(c -> c.month().equals(key)).findFirst()
                    .orElse(new Month(key, 0, 0)));
        }
        return new Stats(totals[0], totals[1], year, List.copyOf(byMonth));
    }

    /** The event a record belongs to, found from any of its members. */
    public Optional<Event> event(String jcat) {
        List<String[]> anchor = jdbc.query(
                "SELECT o.parent, o.separation_text FROM gcat_objects o WHERE o.jcat = ? AND " + VISIBLE,
                (rs, row) -> new String[] {rs.getString("parent"), rs.getString("separation_text")}, jcat);
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        String parentId = anchor.getFirst()[0];
        String separationText = anchor.getFirst()[1];
        String members = "FROM gcat_objects o WHERE o.parent = ? AND o.separation_text = ? AND " + VISIBLE;
        record Group(String firstId, int count, int debris, Date date) {}
        Group group = jdbc.queryForObject(
                "SELECT min(o.jcat) AS first_id, count(*) AS children,"
                        + " count(*) FILTER (WHERE o.type LIKE 'D%') AS debris, min(o.separation_at) AS at,"
                        + " min(o.separation_precision) AS precision, bool_or(o.separation_uncertain) AS uncertain "
                        + members,
                (rs, row) -> new Group(rs.getString("first_id"), rs.getInt("children"), rs.getInt("debris"),
                        new Date(separationText, instant(rs, "at"), rs.getString("precision"), rs.getBoolean("uncertain"))),
                parentId, separationText);
        List<SpaceObject> children = jdbc.query(
                "SELECT " + COLUMNS + IMAGE + WITH_IMAGE
                        + " WHERE o.parent = ? AND o.separation_text = ? AND " + VISIBLE
                        + " ORDER BY o.satcat NULLS LAST, o.jcat LIMIT ?",
                (rs, row) -> object(rs), parentId, separationText, MAX_CHILDREN);
        SpaceObject parent = find(parentId).orElse(null);
        SpaceObject grandparent = parent == null ? null : find(parentOf(parentId)).orElse(null);
        return Optional.of(new Event(group.firstId(),
                group.debris() == group.count() ? Kind.FRAGMENTATION : Kind.RELEASE,
                group.date(), parent, grandparent, List.copyOf(children), group.count(), updatedAt()));
    }

    /** When the catalogue was last imported, or null before the first import. */
    public Instant updatedAt() {
        return jdbc.queryForObject("SELECT max(imported_at) FROM gcat_files",
                (rs, row) -> instant(rs, 1));
    }

    private String parentOf(String jcat) {
        List<String> parent = jdbc.queryForList("SELECT parent FROM gcat_objects WHERE jcat = ?", String.class, jcat);
        return parent.isEmpty() ? null : parent.getFirst();
    }

    private Optional<SpaceObject> find(String jcat) {
        if (jcat == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + IMAGE + WITH_IMAGE + " WHERE o.jcat = ?",
                (rs, row) -> object(rs), jcat).stream().findFirst();
    }

    private static SpaceObject object(ResultSet rs) throws SQLException {
        String launchText = rs.getString("launch_text");
        Date launch = launchText == null ? null
                : new Date(launchText, instant(rs, "launch_at"), rs.getString("launch_precision"), launchText.endsWith("?"));
        return new SpaceObject(
                rs.getString("jcat"),
                integer(rs, "satcat"),
                rs.getString("name"),
                rs.getString("payload_name"),
                rs.getString("piece"),
                role(rs.getString("type")),
                rs.getString("owner"),
                rs.getString("state"),
                number(rs, "mass_kg"),
                launch,
                new Orbit(number(rs, "perigee_km"), finite(number(rs, "apogee_km")),
                        number(rs, "inclination_deg"), rs.getString("op_orbit")),
                rs.getString("decay_text") == null,
                new Evidence(rs.getString("jcat"), integer(rs, "satcat"), rs.getString("piece"),
                        rs.getString("name"), rs.getString("payload_name"), rs.getString("parent_text"),
                        rs.getString("separation_text"), rs.getString("owner"), rs.getString("status")),
                image(rs));
    }

    private static Image image(ResultSet rs) throws SQLException {
        String url = rs.getString("thumb_url");
        if (url == null) {
            return null;
        }
        return new Image(url, integer(rs, "thumb_width"), integer(rs, "thumb_height"), rs.getString("author"),
                rs.getString("licence"), rs.getString("licence_url"), rs.getString("description_url"));
    }

    /** The first letter of the record's type: payload, rocket stage, component, debris. */
    static String role(String type) {
        if (type == null || type.isBlank()) {
            return "other";
        }
        return switch (type.charAt(0)) {
            case 'P' -> "payload";
            case 'R' -> "rocket-stage";
            case 'C' -> "component";
            case 'D' -> "debris";
            default -> "other";
        };
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double number(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** An escape trajectory's apogee is stored as infinity, which JSON cannot carry. */
    private static Double finite(Double value) {
        return value == null || value.isInfinite() ? null : value;
    }
}
