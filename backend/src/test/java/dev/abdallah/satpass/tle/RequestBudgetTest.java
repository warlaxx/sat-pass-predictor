package dev.abdallah.satpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The cap that keeps an authenticated source from being called into a suspension.
 *
 * <p>Both windows slide, so the clock is moved by hand rather than waited on: a test that
 * sleeps for a minute to prove a per-minute rule is a test nobody runs.
 */
class RequestBudgetTest {

    private static final Instant START = Instant.parse("2026-09-18T20:00:00Z");

    @Test
    void grantsUpToThePerMinuteCapAndThenRefuses() {
        MutableClock clock = new MutableClock(START);
        RequestBudget budget = new RequestBudget(3, 100, clock);

        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
    }

    /** Sliding, not a tumbling bucket reset on the minute. */
    @Test
    void grantsAgainOnceTheOldestCallLeavesTheMinute() {
        MutableClock clock = new MutableClock(START);
        RequestBudget budget = new RequestBudget(2, 100, clock);
        budget.tryAcquire();
        clock.advance(Duration.ofSeconds(30));
        budget.tryAcquire();

        assertThat(budget.tryAcquire()).isFalse();

        // 61 s after the first call, 31 s after the second: exactly one slot comes back.
        clock.advance(Duration.ofSeconds(31));
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void enforcesTheHourlyCapAcrossManyMinutes() {
        MutableClock clock = new MutableClock(START);
        RequestBudget budget = new RequestBudget(2, 5, clock);

        for (int i = 0; i < 5; i++) {
            assertThat(budget.tryAcquire()).as("call %d", i).isTrue();
            clock.advance(Duration.ofMinutes(2));
        }
        assertThat(budget.tryAcquire()).isFalse();

        // The first call falls out of the hour at 60 min; it was made at t=0 and we are
        // at t=10 min, so the budget stays shut until then.
        clock.advance(Duration.ofMinutes(51));
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void refusesAMeaninglessConfiguration() {
        MutableClock clock = new MutableClock(START);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RequestBudget(0, 100, clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RequestBudget(10, 5, clock))
                .withMessageContaining("hourly");
    }
}
