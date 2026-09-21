package dev.abdallah.satpass.access;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** One self-serve key per immutable GitHub identity; rotation keeps its quota history. */
public class AccountService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AccessService access;
    private final Clock clock;

    public AccountService(JdbcTemplate jdbc, TransactionTemplate transaction, AccessService access, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.access = access;
        this.clock = clock;
    }

    public record Dashboard(UUID keyId, String plan, boolean active, long usedToday,
                            int dailyLimit, int minuteLimit, LocalDate usageDay, Long usedMonth, Integer monthlyLimit) {}

    public void register(String githubId) {
        validate(githubId);
        jdbc.update("INSERT INTO customer_accounts (github_id) VALUES (?) ON CONFLICT DO NOTHING", githubId);
    }

    public Dashboard dashboard(String githubId) {
        validate(githubId);
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var rows = jdbc.queryForList("""
                SELECT k.*, (SELECT COALESCE(SUM(requests), 0) FROM api_usage
                    WHERE key_id = k.id AND usage_day = ?) AS used_today
                FROM customer_accounts a JOIN api_keys k ON k.id = a.key_id WHERE a.github_id = ?
                """, day, githubId);
        if (rows.isEmpty()) return new Dashboard(null, "standard", false, 0, 100, 10, day, null, null);
        var row = rows.getFirst();
        boolean paid = PaidQuota.active(row, clock.instant());
        LocalDate month = day.withDayOfMonth(1);
        Long monthly = paid ? jdbc.queryForObject("SELECT COALESCE(SUM(requests), 0) FROM api_usage WHERE key_id = ? AND usage_day >= ? AND usage_day < ?",
                Long.class, row.get("id"), month, month.plusMonths(1)) : null;
        return new Dashboard((UUID) row.get("id"), paid ? (String) row.get("billing_plan") : (String) row.get("plan"), (Boolean) row.get("active"),
                ((Number) row.get("used_today")).longValue(), ((Number) row.get("daily_limit")).intValue(),
                PaidQuota.minuteLimit(row, clock.instant()), day, monthly,
                paid ? ((Number) row.get("monthly_limit")).intValue() : null);
    }

    public AccessService.IssuedKey regenerate(String githubId) {
        validate(githubId);
        return transaction.execute(status -> {
            UUID keyId = lockedKey(githubId);
            if (keyId == null) {
                var issued = access.issue("github:" + githubId, 100, 10);
                jdbc.update("UPDATE customer_accounts SET key_id = ? WHERE github_id = ?", issued.id(), githubId);
                return issued;
            }
            String secret = access.newSecret();
            // UPDATE takes the same key lock as admission. Limits, plan and counters survive.
            jdbc.update("UPDATE api_keys SET key_hash = ?, active = true WHERE id = ?", AccessService.hash(secret), keyId);
            return new AccessService.IssuedKey(keyId, secret);
        });
    }

    public void revoke(String githubId) {
        validate(githubId);
        transaction.executeWithoutResult(status -> {
            UUID keyId = lockedKey(githubId);
            if (keyId != null) access.revoke(keyId);
        });
    }

    private UUID lockedKey(String githubId) {
        var rows = jdbc.queryForList("SELECT key_id FROM customer_accounts WHERE github_id = ? FOR UPDATE", githubId);
        if (rows.isEmpty()) throw new IllegalStateException("Account has not been provisioned");
        return (UUID) rows.getFirst().get("key_id");
    }

    private static void validate(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Invalid GitHub identity");
    }
}
