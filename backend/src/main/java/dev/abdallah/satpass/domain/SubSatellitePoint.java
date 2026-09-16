package dev.abdallah.satpass.domain;

/**
 * Point au sol a la verticale du satellite, en coordonnees geodesiques WGS84.
 *
 * <p>Type du domaine plutot que le {@code GeodeticPoint} d'Orekit : le domaine ne
 * connait pas Orekit, et ce point part tel quel dans le JSON de l'API puis dans le
 * globe du frontend. Exposer le type d'Orekit aurait fait de sa serialisation — angles
 * en radians, champs derives — un contrat public involontaire.
 *
 * <p>Longitude dans [-180, 180], comme la projection ITRF la produit : c'est aussi la
 * convention des donnees Natural Earth utilisees par le globe.
 */
public record SubSatellitePoint(double latitudeDeg, double longitudeDeg, double altitudeKm) {

    public SubSatellitePoint {
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude hors de [-90, 90] : " + latitudeDeg);
        }
        if (longitudeDeg < -180.0 || longitudeDeg > 180.0) {
            throw new IllegalArgumentException("longitude hors de [-180, 180] : " + longitudeDeg);
        }
        if (!Double.isFinite(altitudeKm)) {
            throw new IllegalArgumentException("altitude non finie : " + altitudeKm);
        }
    }
}
