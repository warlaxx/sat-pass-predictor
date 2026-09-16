package dev.abdallah.satpass.domain;

/**
 * The point on the ground directly below the satellite, in WGS84 geodetic coordinates.
 *
 * <p>A domain type rather than Orekit's {@code GeodeticPoint}: the domain does not know
 * about Orekit, and this point travels as-is into the API's JSON and then into the
 * frontend's globe. Exposing Orekit's type would have turned its serialisation — angles
 * in radians, derived fields — into an unintended public contract.
 *
 * <p>Longitude in [-180, 180], as the ITRF projection produces it; that is also the
 * convention of the Natural Earth data the globe uses.
 */
public record SubSatellitePoint(double latitudeDeg, double longitudeDeg, double altitudeKm) {

    public SubSatellitePoint {
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude outside [-90, 90]: " + latitudeDeg);
        }
        if (longitudeDeg < -180.0 || longitudeDeg > 180.0) {
            throw new IllegalArgumentException("longitude outside [-180, 180]: " + longitudeDeg);
        }
        if (!Double.isFinite(altitudeKm)) {
            throw new IllegalArgumentException("altitude is not finite: " + altitudeKm);
        }
    }
}
