package space.nextpass.usage;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Daily counters in {@code usage_counts} (see {@code V6__usage_counts.sql}).
 *
 * <p>The endpoint is open, so a counter can be inflated by anyone who calls it in a
 * loop; a day's counter stops at {@link #MAX_PER_DAY}, far above any real figure, so that
 * such a run shows as an obvious outlier rather than an unbounded number.
 */
public class UsageRepository {

    static final long MAX_PER_DAY = 10_000;

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UsageEvent event, LocalDate day) {
        jdbc.update("""
                INSERT INTO usage_counts (day, event, count) VALUES (?, ?, 1)
                ON CONFLICT (day, event) DO UPDATE SET count = usage_counts.count + 1
                WHERE usage_counts.count < ?""", day, event.key(), MAX_PER_DAY);
    }
}
