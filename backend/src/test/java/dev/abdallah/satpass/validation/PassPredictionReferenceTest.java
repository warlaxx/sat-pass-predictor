package dev.abdallah.satpass.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.abdallah.satpass.OrekitTest;
import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.passes.PassPredictionService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.propagation.analytical.tle.TLE;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Milestone 2 — regression against the validated reference.
 *
 * <h2>What this test proves, and what it does not</h2>
 *
 * <p>It proves that Orekit reproduces today what it produced on the day the reference was
 * frozen. On its own it proves nothing about the physical correctness of that reference:
 * a wrong computation freezes a wrong reference, which this test would then defend
 * faithfully.
 *
 * <p>Correctness is established by {@code scripts/validate-against-skyfield.py}, which
 * confronts the same file with Skyfield — an independent implementation of SGP4, written
 * in Python, sharing no code with Orekit. The two checks are complementary and neither
 * replaces the other: one watches for drift, the other anchors the truth.
 *
 * <h2>On the tolerances</h2>
 *
 * <p>They are not physical error margins. The measured agreement between Orekit and
 * Skyfield is 0.53 millidegree in elevation and 2.0 millidegrees in azimuth. The
 * tolerances below, a thousand times wider, absorb an internal change in Orekit or a
 * refresh of the data set — not a model error, which would be several orders of magnitude
 * larger and would fail the test.
 *
 * <p>Useful calibration when reading a failure: near AOS, the ISS gains about 0.1 degree
 * of elevation per second. A one-second discrepancy and a 0.1-degree discrepancy
 * therefore describe the same event.
 */
@OrekitTest
class PassPredictionReferenceTest {

    /** Tolerated time drift on AOS, culmination and LOS. */
    private static final ChronoUnit TIME_UNIT = ChronoUnit.SECONDS;
    private static final long TIME_TOLERANCE = 1L;

    /** Tolerated angular drift, in degrees, on elevations and azimuths. */
    private static final double ANGLE_TOLERANCE_DEG = 0.1;

    @Autowired
    PassPredictionService service;

    @Autowired
    DataContext dataContext;

    @Test
    void orekitReproducesTheValidatedReference() {
        ValidationReference reference = ValidationReference.load();
        TLE tle = reference.tle();

        assertThat(reference.window().startsAtTleEpoch())
                .as("this reference assumes a window starting at the TLE epoch")
                .isTrue();
        Instant start = tle.getDate().toInstant(dataContext.getTimeScales());

        List<SatellitePass> computed = service.predictPasses(
                tle,
                reference.observerLocation(),
                start,
                reference.windowDuration(),
                reference.minElevationDeg());

        assertThat(computed)
                .as("number of passes")
                .hasSameSizeAs(reference.passes());

        for (int i = 0; i < computed.size(); i++) {
            SatellitePass actual = computed.get(i);
            ValidationReference.ExpectedPass expected = reference.passes().get(i);

            assertThat(actual.aos())
                    .as("pass %d: AOS", i + 1)
                    .isCloseTo(expected.aos(), within(TIME_TOLERANCE, TIME_UNIT));
            assertThat(actual.maxElevationTime())
                    .as("pass %d: culmination time", i + 1)
                    .isCloseTo(expected.maxElevationTime(), within(TIME_TOLERANCE, TIME_UNIT));
            assertThat(actual.los())
                    .as("pass %d: LOS", i + 1)
                    .isCloseTo(expected.los(), within(TIME_TOLERANCE, TIME_UNIT));

            assertThat(actual.maxElevationDeg())
                    .as("pass %d: maximum elevation", i + 1)
                    .isCloseTo(expected.maxElevationDeg(), within(ANGLE_TOLERANCE_DEG));
            assertThat(actual.aosAzimuthDeg())
                    .as("pass %d: azimuth at AOS", i + 1)
                    .isCloseTo(expected.aosAzimuthDeg(), within(ANGLE_TOLERANCE_DEG));
            assertThat(actual.maxElevationAzimuthDeg())
                    .as("pass %d: azimuth at culmination", i + 1)
                    .isCloseTo(expected.maxElevationAzimuthDeg(), within(ANGLE_TOLERANCE_DEG));
            assertThat(actual.losAzimuthDeg())
                    .as("pass %d: azimuth at LOS", i + 1)
                    .isCloseTo(expected.losAzimuthDeg(), within(ANGLE_TOLERANCE_DEG));
        }
    }

    /**
     * The reference file is shared with the Python script. If its shape changes without
     * the script following, the two checks silently stop being about the same thing —
     * this test makes the break noisy.
     */
    @Test
    void theSharedReferenceIsCompleteAndSelfConsistent() {
        ValidationReference reference = ValidationReference.load();

        assertThat(reference.satellite().tleLine1()).startsWith("1 ");
        assertThat(reference.satellite().tleLine2()).startsWith("2 ");
        assertThat(reference.minElevationDeg()).isBetween(0.0, 90.0);
        assertThat(reference.window().hours()).isPositive();
        assertThat(reference.passes()).isNotEmpty();

        assertThat(reference.passes()).allSatisfy(pass -> {
            assertThat(pass.aos()).isBefore(pass.maxElevationTime());
            assertThat(pass.maxElevationTime()).isBefore(pass.los());
            assertThat(pass.maxElevationDeg()).isGreaterThanOrEqualTo(reference.minElevationDeg());
        });
    }
}
