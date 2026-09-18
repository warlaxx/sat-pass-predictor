package dev.abdallah.satpass.tle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A sliding-window cap on how often a source may be called.
 *
 * <h2>Why this exists</h2>
 * Space-Track publishes limits — its API rules ask for fewer than 30 queries a minute and
 * 300 an hour — and enforces them by throttling, then by suspending the account. An
 * account suspended is a source lost, which is the opposite of what a second source is
 * for.
 *
 * <p>The store already bounds how often <em>one</em> satellite is retried
 * ({@code tle.retry-after}), but nothing bounds the number of <em>distinct</em>
 * satellites: with CelesTrak down, a visitor walking through a list of objects would
 * produce one authenticated call each, as fast as they are asked for. This is the bound
 * that does not depend on anyone's behaviour.
 *
 * <h2>Why it refuses instead of waiting</h2>
 * Blocking would hold a Tomcat thread until the window opens, turning a rate limit into a
 * slow outage of the whole API. Refusing turns it into what it really is — this source
 * cannot answer right now — which the chain and the store already know how to handle.
 */
public final class RequestBudget {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private final int perMinute;
    private final int perHour;
    private final Clock clock;

    /** Grant times over the last hour, oldest first. Bounded by {@code perHour}. */
    private final Deque<Instant> grants = new ArrayDeque<>();

    public RequestBudget(int perMinute, int perHour, Clock clock) {
        if (perMinute <= 0 || perHour <= 0) {
            throw new IllegalArgumentException("a request budget must be strictly positive");
        }
        if (perMinute > perHour) {
            throw new IllegalArgumentException(
                    "the per-minute budget (" + perMinute + ") cannot exceed the hourly one ("
                            + perHour + "): the hourly limit would never bite");
        }
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.clock = clock;
    }

    /** @return true if a call may be made now, consuming one slot. */
    public synchronized boolean tryAcquire() {
        Instant now = clock.instant();
        forget(now);
        if (grants.size() >= perHour || inLastMinute(now) >= perMinute) {
            return false;
        }
        grants.addLast(now);
        return true;
    }

    private void forget(Instant now) {
        Instant cutoff = now.minus(HOUR);
        while (!grants.isEmpty() && grants.peekFirst().isBefore(cutoff)) {
            grants.removeFirst();
        }
    }

    private long inLastMinute(Instant now) {
        Instant cutoff = now.minus(MINUTE);
        long count = 0;
        for (Instant grant : grants) {
            if (!grant.isBefore(cutoff)) {
                count++;
            }
        }
        return count;
    }
}
