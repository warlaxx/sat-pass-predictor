package dev.abdallah.satpass.domain;

/**
 * The position of a ground observer, in WGS84 geodetic coordinates.
 *
 * <p>Altitude is measured above the reference ellipsoid, not above the geoid ("sea
 * level"). The two differ by up to about fifty metres depending on the region; that has
 * no noticeable effect on the time of a pass, but the convention is worth naming rather
 * than leaving to be guessed.
 */
public record ObserverLocation(double latitudeDeg, double longitudeDeg, double altitudeMeters) {

    public ObserverLocation {
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude outside [-90, 90]: " + latitudeDeg);
        }
        if (longitudeDeg < -180.0 || longitudeDeg > 180.0) {
            throw new IllegalArgumentException("longitude outside [-180, 180]: " + longitudeDeg);
        }
        if (!Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("altitude is not finite: " + altitudeMeters);
        }
    }
}
