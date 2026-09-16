package dev.abdallah.satpass.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
 *
 * <p>{@code track} est la meme trajectoire, echantillonnee. Les trois instants ci-dessus
 * sont ce qu'on lit dans un tableau ; {@code track} est ce qu'on trace. Le premier point
 * est l'AOS, le dernier le LOS, et le sommet y figure : les trois dates que l'interface
 * etiquette tombent donc sur la courbe, jamais a cote.
 */
public record SatellitePass(
        Instant aos,
        double aosAzimuthDeg,
        Instant maxElevationTime,
        double maxElevationDeg,
        double maxElevationAzimuthDeg,
        Instant los,
        double losAzimuthDeg,
        List<TrackPoint> track) {

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
        if (track == null || track.size() < 2) {
            throw new IllegalArgumentException("un passage sans trajectoire echantillonnee n'est pas tracable");
        }
        // La copie defensive protege l'invariant verifie juste apres : sans elle,
        // l'appelant pourrait vider la liste une fois le record construit.
        track = List.copyOf(track);
        if (!track.getFirst().instant().equals(aos) || !track.getLast().instant().equals(los)) {
            throw new IllegalArgumentException(
                    "la trajectoire doit commencer a l'AOS (" + aos + ") et finir au LOS (" + los + "), "
                            + "elle va de " + track.getFirst().instant() + " a " + track.getLast().instant());
        }
    }

    public Duration duration() {
        return Duration.between(aos, los);
    }
}
