package dev.abdallah.satpass.access;

import java.time.Instant;

public final class AccessFailure extends RuntimeException {
    private final String code;
    private final int status;
    private final Instant resetsAt;

    public AccessFailure(String code, int status, String detail, Instant resetsAt) {
        super(detail);
        this.code = code;
        this.status = status;
        this.resetsAt = resetsAt;
    }
    public String code() { return code; }
    public int status() { return status; }
    public Instant resetsAt() { return resetsAt; }
    public static AccessFailure unauthorized() {
        return new AccessFailure("invalid-api-key", 401, "A valid active X-API-Key is required.", null);
    }
    public static AccessFailure unavailable() {
        return new AccessFailure("api-access-unavailable", 503, "API access accounting is unavailable. Please retry later.", null);
    }
}
