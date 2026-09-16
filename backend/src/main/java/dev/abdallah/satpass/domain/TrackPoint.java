package dev.abdallah.satpass.domain;

import java.time.Instant;

/**
 * Un point de la trajectoire d'un passage : ou se trouve le satellite a un instant
 * donne, vu du sol et vu de l'espace.
 *
 * <p>Les trois instants d'un {@link SatellitePass} suffisent a remplir un tableau, pas
 * a tracer une courbe. Ce type porte la polyligne dont ont besoin les deux vues de
 * l'interface : la carte du ciel consomme {@code azimuthDeg} et {@code elevationDeg}
 * (repere topocentrique, centre sur l'observateur), le globe consomme
 * {@code subPoint} (repere terrestre, centre sur la Terre). Les deux descriptions
 * viennent du meme etat propage, donc du meme calcul — c'est ce qui garantit que les
 * deux vues racontent la meme chose.
 *
 * @param instant      date UTC de l'echantillon
 * @param azimuthDeg   azimut dans [0, 360), compte depuis le Nord vers l'Est
 * @param elevationDeg elevation geometrique au-dessus de l'horizon, en degres
 * @param rangeKm      distance observateur-satellite, en kilometres
 * @param subPoint     point au sol a la verticale du satellite
 * @param illuminated  vrai si le satellite est eclaire par le Soleil. <b>Vaut
 *                     systematiquement {@code false} jusqu'au jalon 10</b> : le champ
 *                     existe des maintenant pour que l'arrivee du calcul d'eclipse ne
 *                     change ni le contrat de l'API ni une ligne de frontend.
 */
public record TrackPoint(
        Instant instant,
        double azimuthDeg,
        double elevationDeg,
        double rangeKm,
        SubSatellitePoint subPoint,
        boolean illuminated) {

    public TrackPoint {
        if (instant == null) {
            throw new IllegalArgumentException("date de l'echantillon manquante");
        }
        if (subPoint == null) {
            throw new IllegalArgumentException("point sous-satellite manquant");
        }
        if (azimuthDeg < 0.0 || azimuthDeg >= 360.0) {
            throw new IllegalArgumentException("azimut hors de [0, 360) : " + azimuthDeg);
        }
        if (elevationDeg < -90.0 || elevationDeg > 90.0) {
            throw new IllegalArgumentException("elevation hors de [-90, 90] : " + elevationDeg);
        }
        if (!(rangeKm > 0.0)) {
            throw new IllegalArgumentException("distance non strictement positive : " + rangeKm);
        }
    }
}
