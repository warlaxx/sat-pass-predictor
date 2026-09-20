package dev.abdallah.satpass.passes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.TleSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the prediction cache reuses, and — the part that matters — what it refuses to.
 *
 * <p>No Orekit here: the computation is a counter. The rules under test are about
 * identity and invalidation, and a real propagation would only make them slower to check.
 */
class PredictionCacheTest {

    private static final int ISS = 25544;
    private static final Instant NOW = Instant.parse("2021-02-04T08:00:00Z");
    private static final Instant EPOCH = Instant.parse("2021-02-04T03:28:36.316Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final ObserverLocation LYON = new ObserverLocation(45.7578, 4.8320, 170.0);
    private static final Duration WINDOW = Duration.ofHours(24);

    private MeterRegistry meters;
    private PredictionCache cache;
    private AtomicInteger computations;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        cache = new PredictionCache(new PredictionCacheProperties(true, MAX_AGE, 100), meters);
        computations = new AtomicInteger();
    }

    @Test
    void servesAnIdenticalRequestWithoutPropagatingAgain() {
        TleSnapshot elements = snapshot(TleFixtures.issLine1(), TleFixtures.issLine2());

        PassPrediction first = ask(LYON, WINDOW, 10.0, elements, NOW);
        PassPrediction second = ask(LYON, WINDOW, 10.0, elements, NOW.plusSeconds(60));

        assertThat(computations).hasValue(1);
        // Not merely equal: the same object, so the answer is genuinely reused and the
        // response still carries the computedAt of the computation it comes from.
        assertThat(second).isSameAs(first);
        assertThat(second.computedAt()).isEqualTo(NOW);
        assertThat(count("hit")).isOne();
        assertThat(count("miss")).isOne();
    }

    @Test
    void newElementsInvalidateTheEntryWhateverItsAge() {
        ask(LYON, WINDOW, 10.0, snapshot(TleFixtures.issLine1(), TleFixtures.issLine2()), NOW);

        // A republished TLE: same satellite, same request, different orbit. One second
        // later, so the age bound cannot be what causes the recomputation.
        ask(LYON, WINDOW, 10.0, snapshot(otherLine1(), TleFixtures.issLine2()), NOW.plusSeconds(1));

        assertThat(computations).hasValue(2);
        assertThat(count("hit")).isZero();
    }

    @Test
    void stopsServingAnEntryOlderThanTheBound() {
        TleSnapshot elements = snapshot(TleFixtures.issLine1(), TleFixtures.issLine2());

        ask(LYON, WINDOW, 10.0, elements, NOW);
        ask(LYON, WINDOW, 10.0, elements, NOW.plus(MAX_AGE).minusSeconds(1));
        assertThat(computations).hasValue(1);

        // The elements have not changed; the window start has drifted too far.
        PassPrediction recomputed = ask(LYON, WINDOW, 10.0, elements, NOW.plus(MAX_AGE));
        assertThat(computations).hasValue(2);
        assertThat(recomputed.computedAt()).isEqualTo(NOW.plus(MAX_AGE));
    }

    @Test
    void everyInputOfTheComputationIsPartOfTheKey() {
        TleSnapshot elements = snapshot(TleFixtures.issLine1(), TleFixtures.issLine2());

        ask(LYON, WINDOW, 10.0, elements, NOW);
        ask(LYON, WINDOW, 10.0, elements, NOW);               // the reference: one hit
        ask(new ObserverLocation(45.7579, 4.8320, 170.0), WINDOW, 10.0, elements, NOW);
        ask(new ObserverLocation(45.7578, 4.8321, 170.0), WINDOW, 10.0, elements, NOW);
        ask(new ObserverLocation(45.7578, 4.8320, 171.0), WINDOW, 10.0, elements, NOW);
        ask(LYON, Duration.ofHours(25), 10.0, elements, NOW);
        ask(LYON, WINDOW, 10.5, elements, NOW);
        askFor(25545, LYON, WINDOW, 10.0, elements, NOW);

        // Seven distinct requests, and exactly one repetition among the eight calls.
        assertThat(computations).hasValue(7);
        assertThat(count("hit")).isOne();
    }

    @Test
    void theTwoSignedZeroesAreTheSameMeridian() {
        TleSnapshot elements = snapshot(TleFixtures.issLine1(), TleFixtures.issLine2());

        ask(new ObserverLocation(0.0, 0.0, 0.0), WINDOW, 10.0, elements, NOW);
        ask(new ObserverLocation(-0.0, -0.0, -0.0), WINDOW, 10.0, elements, NOW);

        assertThat(computations).hasValue(1);
    }

    @Test
    void disabledMeansEveryCallComputes() {
        cache = new PredictionCache(new PredictionCacheProperties(false, MAX_AGE, 100), meters);
        TleSnapshot elements = snapshot(TleFixtures.issLine1(), TleFixtures.issLine2());

        ask(LYON, WINDOW, 10.0, elements, NOW);
        ask(LYON, WINDOW, 10.0, elements, NOW);

        assertThat(computations).hasValue(2);
        assertThat(count("hit")).isZero();
        // Misses are still counted and still timed: the measurement the cache is judged
        // on must survive the cache being turned off.
        assertThat(count("miss")).isEqualTo(2.0);
        assertThat(meters.get("satpass.prediction.duration").timer().count()).isEqualTo(2);
    }

    private PassPrediction ask(ObserverLocation observer, Duration window, double minElevation,
                               TleSnapshot elements, Instant now) {
        return askFor(ISS, observer, window, minElevation, elements, now);
    }

    private PassPrediction askFor(int noradId, ObserverLocation observer, Duration window,
                                  double minElevation, TleSnapshot elements, Instant now) {
        return cache.get(noradId, observer, window, minElevation, elements, now, () -> {
            computations.incrementAndGet();
            return new PassPrediction(elements, observer, minElevation, now, List.of());
        });
    }

    private double count(String result) {
        return meters.get("satpass.predictions").tag("result", result).counter().count();
    }

    private static TleSnapshot snapshot(String line1, String line2) {
        return new TleSnapshot(ISS, TleFixtures.issName(), line1, line2, EPOCH, NOW, "celestrak");
    }

    /**
     * The reference line 1 with its mean-motion derivative changed — a different orbit,
     * still 69 columns, which is all {@code TleSnapshot} checks. The checksum is not
     * recomputed because nothing here parses the line: this cache compares strings.
     */
    private static String otherLine1() {
        String line1 = TleFixtures.issLine1();
        char digit = line1.charAt(35);
        return line1.substring(0, 35) + (digit == '9' ? '8' : (char) (digit + 1)) + line1.substring(36);
    }
}
