package space.nextpass.passes;

import java.util.Map;
import org.hipparchus.util.FastMath;

/**
 * How bright an illuminated satellite looks (ABD-35): the visual magnitude a diffusely
 * reflecting sphere would have, the model Heavens-Above and McCants' catalogue use.
 *
 * <pre>m = m₀ − 15 + 5·log₁₀(d) − 2.5·log₁₀(sin φ + (π − φ)·cos φ)</pre>
 *
 * where {@code m₀} is the satellite's standard magnitude (at 1 000 km, half lit, phase
 * angle 90°), {@code d} the range in kilometres and {@code φ} the phase angle, at the
 * satellite, between the Sun and the observer. Lower is brighter: Sirius is −1.5, the
 * faintest star a suburban sky shows is about +4.
 *
 * <h2>Limits</h2>
 * A sphere is not a station with solar panels: real passes can be a magnitude brighter
 * or fainter, and flares are not modelled. Atmospheric extinction near the horizon is
 * ignored. The magnitude is an estimate to decide whether to go out, not a measurement.
 *
 * <h2>Which satellites</h2>
 * Only those whose standard magnitude is established. A generic default would be wrong
 * by several magnitudes for most objects — a Starlink is not a rocket body — and a wrong
 * "naked eye" sends someone out for nothing, so the others get no magnitude at all.
 */
public final class Brightness {

    /**
     * Standard magnitudes, by NORAD number. ISS: −1.8, the value the issue and the usual
     * observing guides give for the complete station.
     */
    static final Map<Integer, Double> STANDARD_MAGNITUDES = Map.of(25544, -1.8);

    private Brightness() {}

    /** The satellite's standard magnitude, or null when none is established. */
    public static Double standardMagnitude(int noradId) {
        return STANDARD_MAGNITUDES.get(noradId);
    }

    /**
     * @param standardMagnitude at 1 000 km and a 90° phase angle
     * @param rangeKm           observer-to-satellite distance
     * @param phaseAngleRad     angle at the satellite between the Sun and the observer, in
     *                          [0, π]: 0 is full phase (Sun behind the observer)
     */
    public static double magnitude(double standardMagnitude, double rangeKm, double phaseAngleRad) {
        if (!(rangeKm > 0.0)) {
            throw new IllegalArgumentException("range is not strictly positive: " + rangeKm);
        }
        double phi = FastMath.max(0.0, FastMath.min(FastMath.PI, phaseAngleRad));
        // At φ = π the satellite shows only its dark side: the function is 0, the magnitude
        // infinite. A floor keeps the number finite and, in practice, invisible.
        double phase = FastMath.max(1.0e-6, FastMath.sin(phi) + (FastMath.PI - phi) * FastMath.cos(phi));
        return standardMagnitude - 15.0 + 5.0 * FastMath.log10(rangeKm) - 2.5 * FastMath.log10(phase);
    }
}
