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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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

    /**
     * Range-rate tolerance against the sampled ranges, in km/s.
     *
     * <p>This bounds the truncation error of a trapezoid over 10 s, not the range rate
     * itself, which comes from the velocity and is exact to the model. The error grows
     * with how sharply the range turns at culmination, so the highest pass is the worst:
     * 10 m/s measured on the reference day, for a culmination at 50 degrees. 50 m/s
     * leaves room for a closer-to-zenith pass while a wrong sign or unit — kilometres
     * per second off — still cannot pass.
     */
    private static final double RANGE_RATE_TOLERANCE_KM_S = 0.05;

    @Autowired
    PassPredictionService service;

    @Autowired
    DataContext dataContext;

    private List<SatellitePass> passes;

    @BeforeAll
    void predictOnce() {
        TLE iss = TleFixtures.iss();
        Instant epoch = iss.getDate().toInstant(dataContext.getTimeScales());
        passes = service.predictPasses(iss, LYON, epoch, Duration.ofHours(24), MIN_ELEVATION_DEG);
    }

    /**
     * Exit criterion of milestone 3: the polyline spans exactly the pass, and both of its
     * ends sit at the requested elevation threshold.
     */
    @Test
    void theTrackSpansExactlyThePassAndEndsAtTheElevationThreshold() {
        assertThat(passes).isNotEmpty().allSatisfy(pass -> {
            TrackPoint first = pass.track().getFirst();
            TrackPoint last = pass.track().getLast();

            assertThat(first).as("first point = AOS").isEqualTo(pass.aos());
            assertThat(last).as("last point = LOS").isEqualTo(pass.los());

            assertThat(first.elevationDeg())
                    .as("elevation at AOS")
                    .isCloseTo(MIN_ELEVATION_DEG, within(ELEVATION_TOLERANCE_DEG));
            assertThat(last.elevationDeg())
                    .as("elevation at LOS")
                    .isCloseTo(MIN_ELEVATION_DEG, within(ELEVATION_TOLERANCE_DEG));
        });
    }

    /**
     * The culmination falls on the curve, and it is its highest point.
     *
     * <p>Without that guarantee the interface would draw the culmination marker beside
     * the track: near the zenith the ISS gains several degrees of elevation in a few
     * seconds, and the culmination, found by zeroing the derivative, does not fall on a
     * multiple of {@value #TRACK_STEP_SECONDS} s. It is a flaw visible to the eye, hence
     * a test.
     *
     * <p>Membership is now an invariant of the record, so the first assertion can no
     * longer fail on its own; it is kept because it is the property the interface
     * depends on, and a test that states it survives a change of representation. The
     * second assertion is the one with teeth: it checks that the extremum Orekit
     * returned really is the maximum of the sampled curve, which the detector alone does
     * not guarantee.
     */
    @Test
    void theCulminationIsTheHighestPointOfTheTrack() {
        assertThat(passes).allSatisfy(pass -> {
            assertThat(pass.track())
                    .as("the culmination is one of the drawn points")
                    .contains(pass.culmination());

            assertThat(pass.track())
                    .as("no point of the track goes above the culmination")
                    .allSatisfy(point -> assertThat(point.elevationDeg())
                            .isLessThanOrEqualTo(
                                    pass.culmination().elevationDeg() + ELEVATION_TOLERANCE_DEG));
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
        assertThat(passes).allSatisfy(pass -> {
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
        assertThat(passes).allSatisfy(pass -> {
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
        assertThat(passes).allSatisfy(pass -> assertThat(pass.track()).allSatisfy(point -> {
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

        }));
    }

    /** The domain invariant is checked on construction, not merely documented. */
    @Test
    void aPassCannotBeBuiltWithATrackThatDoesNotMatchItsBounds() {
        SatellitePass pass = passes.getFirst();
        List<TrackPoint> amputated = pass.track().subList(1, pass.track().size());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SatellitePass(
                        pass.aos(), pass.culmination(), pass.los(), amputated))
                .withMessageContaining("AOS");
    }

    /**
     * The culmination has to be a point of the track, not merely a date that falls
     * between AOS and LOS. That is the invariant that lets the API publish the three
     * phases without looking anything up.
     */
    @Test
    void aPassCannotBeBuiltWithACulminationThatIsNotInItsTrack() {
        SatellitePass pass = passes.getFirst();
        TrackPoint takenFromAnotherPass = passes.get(1).culmination();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SatellitePass(
                        pass.aos(), takenFromAnotherPass, pass.los(), pass.track()))
                .withMessageContaining("culmination");
    }

    /**
     * The range rate is the derivative of the range the same track publishes.
     *
     * <p>It comes from the propagated velocity, so the sampled ranges are an independent
     * check of it — and of its sign convention, the one mistake a Doppler client cannot
     * detect by itself. Between two consecutive points, the change in range must equal
     * the trapezoidal integral of the two rates. Not a central difference: the
     * culmination is inserted off the 10 s grid, the spacing around it is uneven, and a
     * central difference over uneven spacing is only first-order accurate — near the
     * culmination, where the range turns fastest, that alone is 0.2 km/s. The trapezoid
     * stays second order whatever the spacing. A wrong sign or a factor of 1000 is
     * kilometres per second off and cannot hide in the tolerance.
     */
    @Test
    void theRangeRateIsTheDerivativeOfTheRange() {
        assertThat(passes).allSatisfy(pass -> {
            List<TrackPoint> track = pass.track();
            for (int i = 0; i < track.size() - 1; i++) {
                TrackPoint from = track.get(i);
                TrackPoint to = track.get(i + 1);
                double seconds = Duration.between(from.instant(), to.instant()).toNanos() / 1.0e9;
                double meanRate = (from.rangeRateKmS() + to.rangeRateKmS()) / 2.0;
                assertThat(meanRate)
                        .as("mean range rate between %s and %s", from.instant(), to.instant())
                        .isCloseTo((to.rangeKm() - from.rangeKm()) / seconds,
                                within(RANGE_RATE_TOLERANCE_KM_S));
            }
        });
    }

    /** The satellite comes towards the observer at AOS and goes away at LOS. */
    @Test
    void theSatelliteApproachesAtAosAndRecedesAtLos() {
        assertThat(passes).allSatisfy(pass -> {
            assertThat(pass.aos().rangeRateKmS()).as("approaching at AOS").isNegative();
            assertThat(pass.los().rangeRateKmS()).as("receding at LOS").isPositive();
            // Below orbital velocity, and well above zero at the threshold: a low-orbit
            // range rate at 10 degrees of elevation lives between the two.
            assertThat(Math.abs(pass.aos().rangeRateKmS())).isBetween(1.0, 7.8);
            assertThat(Math.abs(pass.los().rangeRateKmS())).isBetween(1.0, 7.8);
        });
    }
}
