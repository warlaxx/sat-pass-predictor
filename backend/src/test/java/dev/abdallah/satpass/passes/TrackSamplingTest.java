package dev.abdallah.satpass.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import dev.abdallah.satpass.OrekitTest;
import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.TrackPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.propagation.analytical.tle.TLE;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Milestone 3 — the sampled track.
 *
 * <p>These tests are about the <em>shape</em> of the polyline, not about frozen values:
 * the physical correctness of the positions is established elsewhere, by the milestone 2
 * reference and its Skyfield validation. What is checked here is what the interface
 * depends on and what a refactoring would break without a sound — that the curve starts
 * at AOS, ends at LOS, goes through the culmination, and that the announced step is the
 * one applied.
 */
@OrekitTest
class TrackSamplingTest {

    private static final ObserverLocation LYON = new ObserverLocation(45.7578, 4.8320, 170.0);
    private static final double MIN_ELEVATION_DEG = 10.0;

    /** Must stay equal to {@code PassPredictionService.TRACK_STEP_SECONDS}. */
    private static final long TRACK_STEP_SECONDS = 10L;

    /**
     * Angular tolerance, in degrees.
     *
     * <p>Calibrated on the accuracy of the root search (1 ms) and on the angular velocity
     * of the ISS near the horizon, about 0.1 degree per second: the expected discrepancy
     * is on the order of 1e-4 degree. A thousandth of a degree leaves an order of
     * magnitude of margin without letting anything significant through.
     */
    private static final double ELEVATION_TOLERANCE_DEG = 1.0e-3;

    @Autowired
    PassPredictionService service;

    @Autowired
    DataContext dataContext;

    private List<SatellitePass> passes() {
        TLE iss = TleFixtures.iss();
        Instant epoch = iss.getDate().toInstant(dataContext.getTimeScales());
        return service.predictPasses(iss, LYON, epoch, Duration.ofHours(24), MIN_ELEVATION_DEG);
    }

    /**
     * Exit criterion of milestone 3: the polyline spans exactly the pass, and both of its
     * ends sit at the requested elevation threshold.
     */
    @Test
    void theTrackSpansExactlyThePassAndEndsAtTheElevationThreshold() {
        assertThat(passes()).isNotEmpty().allSatisfy(pass -> {
            TrackPoint first = pass.track().getFirst();
            TrackPoint last = pass.track().getLast();

            assertThat(first.instant()).as("first point = AOS").isEqualTo(pass.aos());
            assertThat(last.instant()).as("last point = LOS").isEqualTo(pass.los());

            assertThat(first.elevationDeg())
                    .as("elevation at AOS")
                    .isCloseTo(MIN_ELEVATION_DEG, within(ELEVATION_TOLERANCE_DEG));
            assertThat(last.elevationDeg())
                    .as("elevation at LOS")
                    .isCloseTo(MIN_ELEVATION_DEG, within(ELEVATION_TOLERANCE_DEG));
        });
    }

    /**
     * The culmination falls on the curve.
     *
     * <p>Without that guarantee the interface would draw the culmination marker beside
     * the track: near the zenith the ISS gains several degrees of elevation in a few
     * seconds, and the culmination, found by zeroing the derivative, does not fall on a
     * multiple of {@value #TRACK_STEP_SECONDS} s. It is a flaw visible to the eye, hence
     * a test.
     */
    @Test
    void theCulminationIsOneOfTheTrackPoints() {
        assertThat(passes()).allSatisfy(pass -> {
            TrackPoint apex = pass.track().stream()
                    .filter(point -> point.instant().equals(pass.maxElevationTime()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "culmination " + pass.maxElevationTime() + " is not in the track"));

            assertThat(apex.elevationDeg())
                    .as("the top of the curve is the announced maximum")
                    .isCloseTo(pass.maxElevationDeg(), within(ELEVATION_TOLERANCE_DEG));

            assertThat(pass.track())
                    .as("no point of the track goes above that maximum")
                    .allSatisfy(point -> assertThat(point.elevationDeg())
                            .isLessThanOrEqualTo(pass.maxElevationDeg() + ELEVATION_TOLERANCE_DEG));
        });
    }

    /**
     * The applied step is the announced one: points in chronological order, never further
     * apart than the nominal step, never coincident.
     *
     * <p>Two points a few microseconds apart are not a cosmetic detail: a zero-length
     * segment in an SVG polyline produces joint artefacts, and the playback of the
     * transport bar would jump.
     */
    @Test
    void samplesAreChronologicalAndRegularlySpaced() {
        assertThat(passes()).allSatisfy(pass -> {
            List<TrackPoint> track = pass.track();
            for (int i = 1; i < track.size(); i++) {
                Duration gap = Duration.between(track.get(i - 1).instant(), track.get(i).instant());
                assertThat(gap)
                        .as("interval between points %d and %d", i - 1, i)
                        .isGreaterThan(Duration.ofMillis(1))
                        .isLessThanOrEqualTo(Duration.ofSeconds(TRACK_STEP_SECONDS));
            }
        });
    }

    /**
     * The number of points follows the duration of the pass, since the step is fixed.
     * That is the accepted trade-off of the choice: we test it rather than suffer it.
     */
    @Test
    void theNumberOfSamplesFollowsTheDurationOfThePass() {
        assertThat(passes()).allSatisfy(pass -> {
            long gridPoints = pass.duration().toSeconds() / TRACK_STEP_SECONDS;
            // Interior grid, plus AOS, culmination and LOS, minus the duplicates dropped:
            // we bracket generously rather than reproduce the service's arithmetic here.
            assertThat(pass.track().size()).isBetween((int) gridPoints, (int) gridPoints + 4);
        });
    }

    /**
     * The sub-satellite point is plausible: a low Earth orbit, neither a point on the
     * ground nor a geostationary satellite. A frame confusion — ITRF taken for TEME —
     * would show up here, as a latitude exceeding the inclination of the orbit.
     */
    @Test
    void everySampleCarriesAPlausibleSubSatellitePoint() {
        assertThat(passes()).allSatisfy(pass -> assertThat(pass.track()).allSatisfy(point -> {
            assertThat(point.subPoint().altitudeKm())
                    .as("altitude of the ISS")
                    .isBetween(300.0, 500.0);
            assertThat(point.subPoint().latitudeDeg())
                    .as("the ISS never exceeds its orbital inclination, 51.6 degrees")
                    .isBetween(-52.0, 52.0);
            assertThat(point.subPoint().longitudeDeg()).isBetween(-180.0, 180.0);

            // At 10 degrees of elevation the ISS is about 1500 km away; at the zenith, at
            // its altitude. Outside that range, the geometry of the pass is wrong.
            assertThat(point.rangeKm()).isBetween(300.0, 1800.0);

            assertThat(point.illuminated())
                    .as("the eclipse computation arrives at milestone 10; until then the field is neutral")
                    .isFalse();
        }));
    }

    /** The domain invariant is checked on construction, not merely documented. */
    @Test
    void aPassCannotBeBuiltWithATrackThatDoesNotMatchItsBounds() {
        SatellitePass pass = passes().getFirst();
        List<TrackPoint> amputated = pass.track().subList(1, pass.track().size());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SatellitePass(
                        pass.aos(), pass.aosAzimuthDeg(),
                        pass.maxElevationTime(), pass.maxElevationDeg(), pass.maxElevationAzimuthDeg(),
                        pass.los(), pass.losAzimuthDeg(),
                        amputated))
                .withMessageContaining("AOS");
    }
}
