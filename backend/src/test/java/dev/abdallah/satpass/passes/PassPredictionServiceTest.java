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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.orekit.data.DataContext;
import org.orekit.propagation.analytical.tle.TLE;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The prediction service on the reference TLE.
 *
 * <p>The 24-hour window is propagated once for the whole class, not once per test: each
 * call costs one propagation over the window plus one per pass found. Six tests reading
 * the same five passes have no reason to pay for thirty propagations.
 */
@OrekitTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassPredictionServiceTest {

    private static final ObserverLocation LYON = new ObserverLocation(45.7578, 4.8320, 170.0);
    private static final double MIN_ELEVATION_DEG = 10.0;

    @Autowired
    PassPredictionService service;

    @Autowired
    DataContext dataContext;

    private TLE iss;
    private Instant epoch;
    private List<SatellitePass> passes;

    /** Window start: the TLE epoch, where SGP4 is at its most reliable. */
    @BeforeAll
    void predictOnce() {
        iss = TleFixtures.iss();
        epoch = iss.getDate().toInstant(dataContext.getTimeScales());
        passes = service.predictPasses(iss, LYON, epoch, Duration.ofHours(24), MIN_ELEVATION_DEG);
    }

    @Test
    void producesAPlausibleNumberOfPassesOverTwentyFourHours() {
        // The ISS flies over a mid-latitude about 16 times a day, but only a minority of
        // those orbits comes close enough to the observer. Four to six passes above 10
        // degrees is the expected order of magnitude; five is the exact value for this
        // TLE, hence a regression anchor.
        assertThat(passes).hasSize(5);
    }

    @Test
    void passesAreOrderedAndDisjoint() {
        assertThat(passes).isSortedAccordingTo(
                (a, b) -> a.aos().instant().compareTo(b.aos().instant()));
        for (int i = 1; i < passes.size(); i++) {
            assertThat(passes.get(i).aos().instant())
                    .as("pass %d starts after the previous one ends", i)
                    .isAfter(passes.get(i - 1).los().instant());
        }
    }

    @Test
    void everyPassIsPhysicallyCoherent() {
        assertThat(passes).allSatisfy(pass -> {
            assertThat(pass.culmination().elevationDeg()).isGreaterThanOrEqualTo(MIN_ELEVATION_DEG);
            assertThat(pass.culmination().instant())
                    .isAfter(pass.aos().instant())
                    .isBefore(pass.los().instant());

            // A low Earth orbit crosses the sky in a few minutes. A duration of several
            // hours would signal a confusion of frame or of time scale.
            assertThat(pass.duration())
                    .isGreaterThan(Duration.ofSeconds(30))
                    .isLessThan(Duration.ofMinutes(15));

            assertThat(pass.aos().azimuthDeg()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
            assertThat(pass.culmination().azimuthDeg()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
            assertThat(pass.los().azimuthDeg()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
        });
    }

    /**
     * A pass already under way when the window opens is discarded, rather than returned
     * with an AOS invented at the edge of the window.
     */
    @Test
    void discardsAPassAlreadyUnderwayWhenTheWindowOpens() {
        Instant oneMinuteIntoTheFirstPass = passes.getFirst().aos().instant().plusSeconds(60);

        List<SatellitePass> truncated = service.predictPasses(
                iss, LYON, oneMinuteIntoTheFirstPass, Duration.ofHours(24), MIN_ELEVATION_DEG);

        assertThat(truncated.getFirst().aos().instant())
                .isEqualTo(passes.get(1).aos().instant());
    }

    @Test
    void rejectsAnEmptyWindow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.predictPasses(iss, LYON, epoch, Duration.ZERO, MIN_ELEVATION_DEG))
                .withMessageContaining("window");
    }

    @Test
    void rejectsAnUnreachableElevationThreshold() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.predictPasses(iss, LYON, epoch, Duration.ofHours(24), 90.0))
                .withMessageContaining("minimum elevation");
    }

    @Test
    void rejectsAnImpossibleLatitude() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ObserverLocation(91.0, 4.8320, 170.0))
                .withMessageContaining("latitude");
    }
}
