package dev.abdallah.satpass.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL serializes admission per key, across processes. No network/propagation under the lock. */
public class AccessService {
    public static final UUID PUBLIC_KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AccessService(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
    }

    public void admit(String rawKey, boolean publicDemo) {
        if (rawKey == null && !publicDemo) throw AccessFailure.unauthorized();
        if (rawKey != null && !rawKey.matches("spp_[A-Za-z0-9_-]{43}")) throw AccessFailure.unauthorized();
        transaction.executeWithoutResult(status -> {
            // An explicit supplied key never falls back to the public identity.
            var keys = rawKey == null
                    ? jdbc.queryForList("SELECT * FROM api_keys WHERE id = ? FOR UPDATE", PUBLIC_KEY)
                    : jdbc.queryForList("SELECT * FROM api_keys WHERE key_hash = ? FOR UPDATE", hash(rawKey));
            if (keys.isEmpty() || !Boolean.TRUE.equals(keys.getFirst().get("active"))) {
                throw AccessFailure.unauthorized();
            }
            var key = keys.getFirst();
            UUID id = (UUID) key.get("id");
            Instant now = clock.instant();
            LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
            Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
            long daily = jdbc.queryForObject("SELECT COALESCE(SUM(requests), 0) FROM api_usage WHERE key_id = ? AND usage_day = ?",
                    Long.class, id, day);
            if (key.get("monthly_limit") != null) {
                LocalDate month = day.withDayOfMonth(1);
                long monthly = jdbc.queryForObject("SELECT COALESCE(SUM(requests), 0) FROM api_usage WHERE key_id = ? AND usage_day >= ? AND usage_day < ?",
                        Long.class, id, month, month.plusMonths(1));
                if (monthly >= ((Number) key.get("monthly_limit")).longValue())
                    throw new AccessFailure("monthly-quota-exceeded", 429, "The monthly request quota has been reached.",
                            month.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            }
            Instant oldMinute = key.get("minute_start") == null ? null : ((Timestamp) key.get("minute_start")).toInstant();
            int minuteUsed = minute.equals(oldMinute) ? ((Number) key.get("minute_used")).intValue() : 0;
            if (daily >= ((Number) key.get("daily_limit")).longValue()) {
                throw new AccessFailure("daily-quota-exceeded", 429, "The daily request quota has been reached.",
                        day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            }
            if (minuteUsed >= ((Number) key.get("minute_limit")).intValue()) {
                throw new AccessFailure("rate-limit-exceeded", 429, "The per-minute request limit has been reached.",
                        minute.plusSeconds(60));
            }
            jdbc.update("UPDATE api_keys SET minute_start = ?, minute_used = ? WHERE id = ?",
                    Timestamp.from(minute), minuteUsed + 1, id);
            // Both URL aliases count as the same endpoint. Rejected admissions do not count.
            jdbc.update("""
                    INSERT INTO api_usage (key_id, usage_day, endpoint, requests) VALUES (?, ?, 'passes', 1)
                    ON CONFLICT (key_id, usage_day, endpoint) DO UPDATE SET requests = api_usage.requests + 1
                    """, id, day);
        });
    }

    public record IssuedKey(UUID id, String secret) {
        @Override public String toString() { return "IssuedKey[id=" + id + ", secret=REDACTED]"; }
    }

    public IssuedKey issue(String owner, int dailyLimit, int minuteLimit) {
        if (owner == null || owner.isBlank() || owner.length() > 200 || dailyLimit < 1 || minuteLimit < 1) {
            throw new IllegalArgumentException("Owner (1–200 characters) and positive limits are required");
        }
        String secret = newSecret();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, key_hash, owner, plan, daily_limit, minute_limit) VALUES (?, ?, ?, 'standard', ?, ?)",
                id, hash(secret), owner, dailyLimit, minuteLimit);
        return new IssuedKey(id, secret);
    }

    public boolean revoke(UUID id) {
        if (PUBLIC_KEY.equals(id)) throw new IllegalArgumentException("The public demo identity cannot be revoked by this command");
        return jdbc.update("UPDATE api_keys SET active = false WHERE id = ?", id) == 1;
    }

    public List<Map<String, Object>> usage(UUID id) {
        return jdbc.queryForList("SELECT usage_day, endpoint, requests FROM api_usage WHERE key_id = ? ORDER BY usage_day DESC", id);
    }

    String newSecret() {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        return "spp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    // High-entropy random credentials, not human passwords: SHA-256 needs no slow password KDF.
    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
