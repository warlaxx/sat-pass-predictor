package space.nextpass.domain;

import java.time.Instant;

/**
 * One point along the track of a pass: where the satellite is at a given instant, seen
 * from the ground and seen from space.
 *
 * <p>The three instants of a {@link SatellitePass} are enough to fill a table, not to
 * draw a curve. This type carries the polyline both views of the interface need: the sky
 * chart consumes {@code azimuthDeg} and {@code elevationDeg} (topocentric frame, centred
 * on the observer), the globe consumes {@code subPoint} (terrestrial frame, centred on
 * the Earth). Both descriptions come from the same propagated state, hence from the same
 * computation — which is what guarantees the two views tell the same story.
 *
 * @param instant      UTC date of the sample
 * @param azimuthDeg   azimuth in [0, 360), measured from North towards East
 * @param elevationDeg geometric elevation above the horizon, in degrees
 * @param rangeKm      observer-to-satellite distance, in kilometres
 * @param rangeRateKmS time derivative of that distance, in km/s: negative while the
 *                     satellite approaches, positive while it recedes. It is the only
 *                     input a Doppler correction needs besides the carrier frequency.
 * @param subPoint     the point on the ground directly below the satellite
 * @param illuminated true when the entire solar disc is clear of Earth's limb
 * @param visible     potentially visible: illuminated and observer's Sun at or below
 *                    -6 degrees. Weather and obstructions are not modelled.
 * @param magnitude   estimated visual magnitude (ABD-35, {@code Brightness}), null when
 *                    the satellite is in shadow or its standard magnitude is unknown
 */
public record TrackPoint(
        Instant instant,
        double azimuthDeg,
        double elevationDeg,
        double rangeKm,
        double rangeRateKmS,
        SubSatellitePoint subPoint,
        boolean illuminated,
        boolean visible,
        Double magnitude) {

    /** A sample without a brightness estimate. */
    public TrackPoint(Instant instant, double azimuthDeg, double elevationDeg, double rangeKm,
                      double rangeRateKmS, SubSatellitePoint subPoint, boolean illuminated, boolean visible) {
        this(instant, azimuthDeg, elevationDeg, rangeKm, rangeRateKmS, subPoint, illuminated, visible, null);
    }

    public TrackPoint {
        if (magnitude != null && (!illuminated || !Double.isFinite(magnitude))) {
            throw new IllegalArgumentException("a magnitude needs an illuminated sample and a finite value: "
                    + magnitude);
        }
        if (visible && !illuminated) {
            throw new IllegalArgumentException("a visible sample must be illuminated");
        }
        if (instant == null) {
            throw new IllegalArgumentException("sample date is missing");
        }
        if (subPoint == null) {
            throw new IllegalArgumentException("sub-satellite point is missing");
        }
        if (azimuthDeg < 0.0 || azimuthDeg >= 360.0) {
            throw new IllegalArgumentException("azimuth outside [0, 360): " + azimuthDeg);
        }
        if (elevationDeg < -90.0 || elevationDeg > 90.0) {
            throw new IllegalArgumentException("elevation outside [-90, 90]: " + elevationDeg);
        }
        if (!(rangeKm > 0.0)) {
            throw new IllegalArgumentException("range is not strictly positive: " + rangeKm);
        }
        if (!Double.isFinite(rangeRateKmS)) {
            throw new IllegalArgumentException("range rate is not finite: " + rangeRateKmS);
        }
    }
}
