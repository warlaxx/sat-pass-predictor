package dev.abdallah.satpass.tle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test advances by hand.
 *
 * <p>The rules under test here — "retry past two hours", "refuse past seven days" — are
 * rules about time. Testing them against the wall clock would mean either waiting, or
 * lowering the thresholds until the real ones are no longer tested.
 */
final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant start) {
        this.instant = start;
    }

    void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
