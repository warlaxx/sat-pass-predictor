package dev.abdallah.satpass.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.abdallah.satpass.OrekitTest;
import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.propagation.analytical.tle.TLE;
import org.springframework.beans.factory.annotation.Autowired;

@OrekitTest
class PassPredictionServiceTest {

    private static final ObserverLocation LYON = new ObserverLocation(45.7578, 4.8320, 170.0);
    private static final double MIN_ELEVATION_DEG = 10.0;

    @Autowired
    PassPredictionService service;

    @Autowired
    DataContext dataContext;

    /** Window start: the TLE epoch, where SGP4 is at its most reliable. */
    private Instant tleEpoch(TLE tle) {
        return tle.getDate().toInstant(dataContext.getTimeScales());
    }

    @Test
    void producesAPlausibleNumberOfPassesOverTwentyFourHours() {
        TLE iss = TleFixtures.iss();

        List<SatellitePass> passes =
                service.predictPasses(iss, LYON, tleEpoch(iss), Duration.ofHours(24), MIN_ELEVATION_DEG);
        // The ISS flies over a mid-latitude about 16 times a day, but only a minority of
        // those orbits comes close enough to the observer. Four to six passes above 10
        // degrees is the expected order of magnitude; five is the exact value for this
        // TLE, hence a regression anchor.
        assertThat(passes).hasSize(5);
    }

    @Test
    void passesAreOrderedAndDisjoint() {
        TLE iss = TleFixtures.iss();

        List<SatellitePass> passes =
                service.predictPasses(iss, LYON, tleEpoch(iss), Duration.ofHours(24), MIN_ELEVATION_DEG);

        assertThat(passes).isSortedAccordingTo((a, b) -> a.aos().compareTo(b.aos()));
        for (int i = 1; i < passes.size(); i++) {
            assertThat(passes.get(i).aos())
                    .as("pass %d starts after the previous one ends", i)
                    .isAfter(passes.get(i - 1).los());
        }
    }

    @Test
    void everyPassIsPhysicallyCoherent() {
        TLE iss = TleFixtures.iss();

        List<SatellitePass> passes =
                service.predictPasses(iss, LYON, tleEpoch(iss), Duration.ofHours(24), MIN_ELEVATION_DEG);

        assertThat(passes).allSatisfy(pass -> {
            assertThat(pass.maxElevationDeg()).isGreaterThanOrEqualTo(MIN_ELEVATION_DEG);
            assertThat(pass.maxElevationTime()).isAfter(pass.aos()).isBefore(pass.los());

            // A low Earth orbit crosses the sky in a few minutes. A duration of several
            // hours would signal a confusion of frame or of time scale.
            assertThat(pass.duration())
                    .isGreaterThan(Duration.ofSeconds(30))
                    .isLessThan(Duration.ofMinutes(15));

            assertThat(pass.aosAzimuthDeg()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
            assertThat(pass.maxElevationAzimuthDeg()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
            assertThat(pass.losAzimuthDeg()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
        });
    }

    /**
     * A pass already under way when the window opens is discarded, rather than returned
     * with an AOS invented at the edge of the window.
     */
    @Test
    void discardsAPassAlreadyUnderwayWhenTheWindowOpens() {
        TLE iss = TleFixtures.iss();
        List<SatellitePass> reference =
                service.predictPasses(iss, LYON, tleEpoch(iss), Duration.ofHours(24), MIN_ELEVATION_DEG);
        Instant oneMinuteIntoTheFirstPass = reference.getFirst().aos().plusSeconds(60);

        List<SatellitePass> truncated = service.predictPasses(
                iss, LYON, oneMinuteIntoTheFirstPass, Duration.ofHours(24), MIN_ELEVATION_DEG);

        assertThat(truncated.getFirst().aos()).isEqualTo(reference.get(1).aos());
    }

    @Test
    void rejectsAnEmptyWindow() {
        TLE iss = TleFixtures.iss();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.predictPasses(iss, LYON, tleEpoch(iss), Duration.ZERO, MIN_ELEVATION_DEG))
                .withMessageContaining("window");
    }

    @Test
    void rejectsAnUnreachableElevationThreshold() {
        TLE iss = TleFixtures.iss();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.predictPasses(iss, LYON, tleEpoch(iss), Duration.ofHours(24), 90.0))
                .withMessageContaining("minimum elevation");
    }

    @Test
    void rejectsAnImpossibleLatitude() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ObserverLocation(91.0, 4.8320, 170.0))
                .withMessageContaining("latitude");
    }
}
