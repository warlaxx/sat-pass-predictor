package dev.abdallah.satpass.access;

import dev.abdallah.satpass.billing.BillingService;
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
                            int dailyLimit, int minuteLimit, LocalDate usageDay, long usedThisMonth, int monthlyLimit) {}

    public void register(String githubId) {
        validate(githubId);
        jdbc.update("INSERT INTO customer_accounts (github_id) VALUES (?) ON CONFLICT DO NOTHING", githubId);
    }

    public Dashboard dashboard(String githubId) {
        validate(githubId);
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var rows = jdbc.queryForList("""
                SELECT k.*, (SELECT COALESCE(SUM(requests), 0) FROM api_usage
                    WHERE key_id = k.id AND usage_day = ?) AS used_today, (SELECT COALESCE(SUM(requests), 0) FROM api_usage
                    WHERE key_id = k.id AND usage_day >= ? AND usage_day < ?) AS used_month
                FROM customer_accounts a JOIN api_keys k ON k.id = a.key_id WHERE a.github_id = ?
                """, day, day.withDayOfMonth(1), day.withDayOfMonth(1).plusMonths(1), githubId);
        if (rows.isEmpty()) {
            String plan = jdbc.queryForObject("SELECT plan FROM customer_accounts WHERE github_id = ?", String.class, githubId);
            return new Dashboard(null, plan, false, 0, Integer.MAX_VALUE,
                    BillingService.minuteLimit(plan), day, 0,
                    BillingService.monthlyLimit(plan));
        }
        var row = rows.getFirst();
        return new Dashboard((UUID) row.get("id"), (String) row.get("plan"), (Boolean) row.get("active"),
                ((Number) row.get("used_today")).longValue(), ((Number) row.get("daily_limit")).intValue(),
                ((Number) row.get("minute_limit")).intValue(), day, ((Number) row.get("used_month")).longValue(),
                ((Number) row.get("monthly_limit")).intValue());
    }

    public AccessService.IssuedKey regenerate(String githubId) {
        validate(githubId);
        return transaction.execute(status -> {
            UUID keyId = lockedKey(githubId);
            if (keyId == null) {
                var issued = access.issue("github:" + githubId, 100, 10);
                jdbc.update("UPDATE customer_accounts SET key_id = ? WHERE github_id = ?", issued.id(), githubId);
                String plan = jdbc.queryForObject("SELECT plan FROM customer_accounts WHERE github_id = ?", String.class, githubId);
                jdbc.update("UPDATE api_keys SET plan = ?, monthly_limit = ?, daily_limit = 2147483647, minute_limit = ? WHERE id = ?",
                        plan, BillingService.monthlyLimit(plan),
                        BillingService.minuteLimit(plan), issued.id());
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
