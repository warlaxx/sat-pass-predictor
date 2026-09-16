package dev.abdallah.satpass.domain;

/**
 * Position d'un observateur au sol, en coordonnees geodesiques WGS84.
 *
 * <p>L'altitude est celle au-dessus de l'ellipsoide de reference, pas au-dessus du
 * geoide (le "niveau de la mer"). L'ecart entre les deux atteint une cinquantaine de
 * metres selon les regions ; c'est sans effet notable sur la date d'un passage, mais
 * autant nommer la convention plutot que de la laisser deviner.
 */
public record ObserverLocation(double latitudeDeg, double longitudeDeg, double altitudeMeters) {

    public ObserverLocation {
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude hors de [-90, 90] : " + latitudeDeg);
        }
        if (longitudeDeg < -180.0 || longitudeDeg > 180.0) {
            throw new IllegalArgumentException("longitude hors de [-180, 180] : " + longitudeDeg);
        }
        if (!Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("altitude non finie : " + altitudeMeters);
        }
    }
}
