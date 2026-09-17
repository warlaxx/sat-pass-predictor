package dev.abdallah.satpass.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.abdallah.satpass.OrekitTest;
import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.TleSnapshot;
import dev.abdallah.satpass.tle.TleStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The joint between the TLE store and the pass computation.
 *
 * <p>What is checked here cannot be checked anywhere else: the <em>two raw lines</em> the
 * domain keeps really do rebuild the same TLE that produced the milestone 2 reference.
 * That is the price of the domain exposing no Orekit type, and this test is what proves
 * the price is zero.
 */
@OrekitTest
class PassQueryServiceTest {

    /** Epoch of the reference TLE. The window starts there, where SGP4 is most reliable. */
    private static final Instant TLE_EPOCH = Instant.parse("2021-02-04T03:28:36.316Z");

    private static final ObserverLocation LYON = new ObserverLocation(45.7578, 4.8320, 170.0);

    @Autowired
    PassPredictionService predictionService;

    private PassQueryService serviceAt(Instant now, TleSnapshot snapshot) {
        TleStore store = mock(TleStore.class);
        when(store.get(25544)).thenReturn(snapshot);
        return new PassQueryService(store, predictionService, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static TleSnapshot referenceSnapshot(Instant fetchedAt) {
        return new TleSnapshot(25544, TleFixtures.issName(), TleFixtures.issLine1(),
                TleFixtures.issLine2(), TLE_EPOCH, fetchedAt, "celestrak");
    }

    @Test
    void rebuildsTheSameOrbitFromTheStoredLines() {
        PassQueryService service = serviceAt(TLE_EPOCH, referenceSnapshot(TLE_EPOCH));

        PassPrediction prediction =
                service.findPasses(25544, LYON, Duration.ofHours(24), 10.0);

        // Five passes over 24 h, exactly like the milestone 2 reference: the two raw
        // lines did rebuild the same TLE.
        assertThat(prediction.passes()).hasSize(5);
        assertThat(prediction.passes().getFirst().aos().instant())
                .isEqualTo(Instant.parse("2021-02-04T12:16:42.325291143Z"));
    }

    @Test
    void carriesTheTleThatWasActuallyUsed() {
        Instant fetchedAt = TLE_EPOCH.plus(Duration.ofHours(3));
        PassQueryService service = serviceAt(TLE_EPOCH.plus(Duration.ofHours(4)),
                referenceSnapshot(fetchedAt));

        PassPrediction prediction =
                service.findPasses(25544, LYON, Duration.ofHours(6), 10.0);

        assertThat(prediction.tle().fetchedAt()).isEqualTo(fetchedAt);
        assertThat(prediction.tle().line1()).isEqualTo(TleFixtures.issLine1());
        assertThat(prediction.minElevationDeg()).isEqualTo(10.0);
    }

    /**
     * The instant of the computation is read once and carried along. Without that, the
     * start of the window and the displayed TLE age would be computed at two different
     * instants.
     */
    @Test
    void readsTheClockOnlyOnceAndPassesItAlong() {
        Instant now = TLE_EPOCH.plus(Duration.ofHours(2));
        PassQueryService service = serviceAt(now, referenceSnapshot(TLE_EPOCH));

        PassPrediction prediction = service.findPasses(25544, LYON, Duration.ofHours(6), 10.0);

        assertThat(prediction.computedAt()).isEqualTo(now);
        assertThat(prediction.passes()).allSatisfy(pass ->
                assertThat(pass.aos().instant()).isAfterOrEqualTo(now));
    }
}
