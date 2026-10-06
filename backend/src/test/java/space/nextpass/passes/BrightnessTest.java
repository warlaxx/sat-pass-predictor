package space.nextpass.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BrightnessTest {

    /** The standard magnitude is defined at 1 000 km and a 90° phase angle. */
    @Test
    void theStandardConditionsGiveTheStandardMagnitude() {
        assertThat(Brightness.magnitude(-1.8, 1000.0, Math.PI / 2)).isCloseTo(-1.8, within(1e-9));
    }

    /** Five magnitudes for every tenfold distance: closer is brighter. */
    @Test
    void distanceDimsByTheInverseSquare() {
        double at400 = Brightness.magnitude(-1.8, 400.0, Math.PI / 2);
        assertThat(at400).isCloseTo(-1.8 + 5 * Math.log10(0.4), within(1e-9));
        assertThat(Brightness.magnitude(-1.8, 2000.0, Math.PI / 2)).isGreaterThan(at400);
    }

    /** Full phase (Sun behind the observer) is brighter than half; a back-lit satellite fades. */
    @Test
    void phaseBrightensFullAndDimsBackLit() {
        double full = Brightness.magnitude(-1.8, 1000.0, 0.0);
        assertThat(full).isCloseTo(-1.8 - 2.5 * Math.log10(Math.PI), within(1e-9));
        assertThat(Brightness.magnitude(-1.8, 1000.0, Math.toRadians(150))).isGreaterThan(-1.8);
        assertThat(Brightness.magnitude(-1.8, 1000.0, Math.PI)).isFinite();
    }

    @Test
    void onlySatellitesWithAnEstablishedStandardMagnitudeGetOne() {
        assertThat(Brightness.standardMagnitude(25544)).isEqualTo(-1.8);
        assertThat(Brightness.standardMagnitude(20580)).isNull();
        assertThatThrownBy(() -> Brightness.magnitude(-1.8, 0.0, 1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
