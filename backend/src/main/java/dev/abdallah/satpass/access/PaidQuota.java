package dev.abdallah.satpass.access;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/** Expired billing never leaves an indefinitely usable paid key after a missed webhook. */
final class PaidQuota {
    static boolean active(Map<String, Object> key, Instant now) {
        return key.get("billing_plan") != null && key.get("monthly_limit") != null
                && key.get("paid_until") instanceof Timestamp until && until.toInstant().isAfter(now);
    }
    static int minuteLimit(Map<String, Object> key, Instant now) {
        return active(key, now) ? ("pro".equals(key.get("billing_plan")) ? 120 : 30)
                : ((Number) key.get("minute_limit")).intValue();
    }
}
