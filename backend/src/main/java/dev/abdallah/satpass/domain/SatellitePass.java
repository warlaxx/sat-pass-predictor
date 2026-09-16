package dev.abdallah.satpass.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Un passage visible : la portion de trajectoire pendant laquelle le satellite reste
 * au-dessus de l'elevation minimale demandee par l'observateur.
 *
 * <p>Toutes les dates sont des {@link Instant} UTC. Le fuseau horaire est un probleme
 * d'affichage, jamais de calcul : il n'entre dans le projet qu'au frontend.
 *
 * <p>Les azimuts sont en degres dans [0, 360), comptes depuis le Nord vers l'Est
 * (convention de {@code TopocentricFrame} d'Orekit). AOS = <i>acquisition of signal</i>,
 * LOS = <i>loss of signal</i> : le vocabulaire des stations sol, repris ici parce que
 * c'est celui qu'emploient les gens a qui ce projet est destine.
 */
public record SatellitePass(
        Instant aos,
        double aosAzimuthDeg,
        Instant maxElevationTime,
        double maxElevationDeg,
        double maxElevationAzimuthDeg,
        Instant los,
        double losAzimuthDeg) {

    public SatellitePass {
        if (aos == null || maxElevationTime == null || los == null) {
            throw new IllegalArgumentException("dates du passage manquantes");
        }
        if (!los.isAfter(aos)) {
            throw new IllegalArgumentException("LOS (" + los + ") doit suivre AOS (" + aos + ")");
        }
        if (maxElevationTime.isBefore(aos) || maxElevationTime.isAfter(los)) {
            throw new IllegalArgumentException(
                    "le maximum d'elevation (" + maxElevationTime + ") doit tomber entre AOS et LOS");
        }
    }

    public Duration duration() {
        return Duration.between(aos, los);
    }
}
