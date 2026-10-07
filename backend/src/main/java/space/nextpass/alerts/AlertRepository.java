package space.nextpass.alerts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code pass_alerts} and {@code alert_email_counts} (see {@code V10__pass_alerts.sql}).
 */
public class AlertRepository {

    /** What a sign-up led to; the page is told the same thing in every case. */
    public sealed interface Signup {
        /** A new or still unconfirmed subscription: its confirmation e-mail may go out. */
        record Pending(AlertSubscription subscription, Instant confirmationSentAt) implements Signup {}

        /** The same address already follows this satellite over this place. */
        record AlreadyConfirmed() implements Signup {}

        /** The address has as many subscriptions as it may have. */
        record TooMany() implements Signup {}
    }

    private static final String COLUMNS = """
            id, email, norad_id, latitude_deg, longitude_deg, min_elevation_deg, max_cloud_percent,
            max_magnitude, time_zone, locale, token, confirmed_at, last_checked_on, last_sent_on""";

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a sign-up, or refreshes the thresholds of one not yet confirmed. A confirmed
     * subscription is left exactly as it is: whoever knows an address must not be able to
     * change what its owner receives.
     */
    public Signup subscribe(AlertRequest request, String token, int maxPerEmail, Instant now) {
        double lat = AlertRequest.rounded(request.lat());
        double lon = AlertRequest.rounded(request.lon());
        List<AlertSubscription> existing = jdbc.query("SELECT " + COLUMNS
                        + " FROM pass_alerts WHERE email = ? AND norad_id = ? AND latitude_deg = ? AND longitude_deg = ?",
                AlertRepository::row, request.email(), request.noradId(), lat, lon);
        if (!existing.isEmpty()) {
            AlertSubscription kept = existing.getFirst();
            if (kept.confirmedAt() != null) {
                return new Signup.AlreadyConfirmed();
            }
            jdbc.update("""
                    UPDATE pass_alerts SET min_elevation_deg = ?, max_cloud_percent = ?, max_magnitude = ?,
                        time_zone = ?, locale = ? WHERE id = ?""",
                    request.minElevationDeg(), request.maxCloudPercent(), request.maxMagnitude(),
                    request.timeZone(), request.locale(), kept.id());
            return new Signup.Pending(find(kept.id()).orElseThrow(), confirmationSentAt(kept.id()));
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM pass_alerts WHERE email = ?", Long.class,
                request.email());
        if (count != null && count >= maxPerEmail) {
            return new Signup.TooMany();
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO pass_alerts (email, norad_id, latitude_deg, longitude_deg, min_elevation_deg,
                    max_cloud_percent, max_magnitude, time_zone, locale, token, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                request.email(), request.noradId(), lat, lon, request.minElevationDeg(), request.maxCloudPercent(),
                request.maxMagnitude(), request.timeZone(), request.locale(), token, Timestamp.from(now));
        return new Signup.Pending(find(id).orElseThrow(), null);
    }

    /** The last confirmation e-mail sent to this address, whichever subscription it was for. */
    public Optional<Instant> lastConfirmationTo(String email) {
        Timestamp last = jdbc.queryForObject("SELECT max(confirmation_sent_at) FROM pass_alerts WHERE email = ?",
                Timestamp.class, email);
        return Optional.ofNullable(last).map(Timestamp::toInstant);
    }

    public void markConfirmationSent(long id, Instant at) {
        jdbc.update("UPDATE pass_alerts SET confirmation_sent_at = ? WHERE id = ?", Timestamp.from(at), id);
    }

    /** Confirms once; a second click on the same link finds it confirmed and says so. */
    public Optional<AlertSubscription> confirm(String token, Instant now) {
        jdbc.update("UPDATE pass_alerts SET confirmed_at = ? WHERE token = ? AND confirmed_at IS NULL",
                Timestamp.from(now), token);
        return jdbc.query("SELECT " + COLUMNS + " FROM pass_alerts WHERE token = ?", AlertRepository::row, token)
                .stream().findFirst();
    }

    public boolean unsubscribe(String token) {
        return jdbc.update("DELETE FROM pass_alerts WHERE token = ?", token) > 0;
    }

    /** Sign-ups nobody confirmed: the address may not even be its owner's. */
    public int purgeUnconfirmed(Instant createdBefore) {
        return jdbc.update("DELETE FROM pass_alerts WHERE confirmed_at IS NULL AND created_at < ?",
                Timestamp.from(createdBefore));
    }

    /**
     * Every confirmed subscription; which of them are due is a question of each one's local
     * time, answered by the caller. Read in full on purpose: the daily e-mail budget keeps
     * this table to a few hundred rows, which one query reads in milliseconds.
     */
    public List<AlertSubscription> confirmed() {
        return jdbc.query("SELECT " + COLUMNS + " FROM pass_alerts WHERE confirmed_at IS NOT NULL ORDER BY id",
                AlertRepository::row);
    }

    public void markChecked(long id, LocalDate day) {
        jdbc.update("UPDATE pass_alerts SET last_checked_on = ? WHERE id = ?", day, id);
    }

    public void markSent(long id, LocalDate day) {
        jdbc.update("UPDATE pass_alerts SET last_checked_on = ?, last_sent_on = ? WHERE id = ?", day, day, id);
    }

    /**
     * Takes one e-mail from the day's budget, or says there is none left: under the kind's
     * own cap and under the total. Synchronized rather than locked in the database because
     * one instance serves the API; two would need a {@code SELECT … FOR UPDATE}.
     */
    public synchronized boolean spendEmail(LocalDate day, String kind, int kindCap, int totalCap) {
        Integer total = jdbc.queryForObject("SELECT coalesce(sum(count), 0) FROM alert_email_counts WHERE day = ?",
                Integer.class, day);
        Integer ofKind = jdbc.queryForObject(
                "SELECT coalesce(sum(count), 0) FROM alert_email_counts WHERE day = ? AND kind = ?",
                Integer.class, day, kind);
        if (total == null || ofKind == null || total >= totalCap || ofKind >= kindCap) {
            return false;
        }
        jdbc.update("""
                INSERT INTO alert_email_counts (day, kind, count) VALUES (?, ?, 1)
                ON CONFLICT (day, kind) DO UPDATE SET count = alert_email_counts.count + 1""", day, kind);
        return true;
    }

    Optional<AlertSubscription> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pass_alerts WHERE id = ?", AlertRepository::row, id)
                .stream().findFirst();
    }

    private Instant confirmationSentAt(long id) {
        Timestamp at = jdbc.queryForObject("SELECT confirmation_sent_at FROM pass_alerts WHERE id = ?",
                Timestamp.class, id);
        return at == null ? null : at.toInstant();
    }

    private static AlertSubscription row(ResultSet rs, int rowNum) throws SQLException {
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        java.sql.Date checked = rs.getDate("last_checked_on");
        java.sql.Date sent = rs.getDate("last_sent_on");
        java.math.BigDecimal magnitude = rs.getBigDecimal("max_magnitude");
        return new AlertSubscription(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getInt("norad_id"),
                rs.getDouble("latitude_deg"),
                rs.getDouble("longitude_deg"),
                rs.getInt("min_elevation_deg"),
                rs.getInt("max_cloud_percent"),
                magnitude == null ? null : magnitude.doubleValue(),
                ZoneId.of(rs.getString("time_zone")),
                rs.getString("locale"),
                rs.getString("token"),
                confirmed == null ? null : confirmed.toInstant(),
                checked == null ? null : checked.toLocalDate(),
                sent == null ? null : sent.toLocalDate());
    }
}
