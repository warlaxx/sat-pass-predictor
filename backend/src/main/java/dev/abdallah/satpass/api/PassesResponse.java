package dev.abdallah.satpass.api;

import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.SubSatellitePoint;
import dev.abdallah.satpass.domain.TrackPoint;
import dev.abdallah.satpass.passes.PassPrediction;
import java.time.Instant;
import java.util.List;

/**
 * Le contrat de l'API, en entier, dans un seul fichier.
 *
 * <p>Ces types sont deliberement separes du domaine. Un record du domaine change quand la
 * physique ou le calcul l'exigent ; un DTO change quand le client l'exige. Les confondre
 * revient a publier chaque refactoring interne comme une rupture d'API — et a s'interdire
 * de renommer un champ du domaine parce qu'un navigateur le lit.
 *
 * <p>La forme suivie est celle documentee dans {@code docs/maquette-interface.html},
 * section « Ce que l'API doit renvoyer ». Elle a ete figee avant l'ecriture du frontend,
 * pour que celui-ci n'ait rien a negocier.
 *
 * <p>Toutes les dates sont des {@link Instant} UTC, serialises en ISO-8601. Le fuseau de
 * l'utilisateur est un probleme d'affichage : le resoudre ici obligerait le serveur a
 * connaitre le navigateur, et rendrait deux reponses identiques incomparables.
 */
public record PassesResponse(SatelliteDto satellite,
                             TleDto tle,
                             ObserverDto observer,
                             double minElevationDeg,
                             Instant computedAt,
                             List<PassDto> passes) {

    public static PassesResponse from(PassPrediction prediction) {
        var snapshot = prediction.tle();
        var observer = prediction.observer();
        return new PassesResponse(
                new SatelliteDto(snapshot.noradId(), snapshot.name()),
                new TleDto(snapshot.epoch(),
                        snapshot.ageSinceEpoch(prediction.computedAt()).toSeconds(),
                        snapshot.source(),
                        snapshot.fetchedAt(),
                        snapshot.line1(),
                        snapshot.line2()),
                new ObserverDto(observer.latitudeDeg(), observer.longitudeDeg(),
                        observer.altitudeMeters()),
                prediction.minElevationDeg(),
                prediction.computedAt(),
                prediction.passes().stream().map(PassDto::from).toList());
    }

    public record SatelliteDto(int noradId, String name) {
    }

    /**
     * L'age est calcule ici une fois, au lieu d'etre laisse au client.
     *
     * <p>Le bandeau d'incertitude de l'interface en depend, et un client qui le
     * recalculerait depuis {@code epoch} et sa propre horloge afficherait un age faux des
     * que celle-ci derive. Les deux lignes brutes accompagnent le tout : elles rendent la
     * reponse verifiable ailleurs, sans quoi rien ne permet de controler le calcul.
     */
    public record TleDto(Instant epoch,
                         long ageSeconds,
                         String source,
                         Instant fetchedAt,
                         String line1,
                         String line2) {
    }

    public record ObserverDto(double latitudeDeg, double longitudeDeg, double altitudeM) {
    }

    public record PassDto(PhaseDto aos,
                          PhaseDto culmination,
                          PhaseDto los,
                          long durationSeconds,
                          List<TrackPointDto> track) {

        /**
         * Les trois phases sont <em>prelevees dans la trajectoire</em>, pas recalculees.
         *
         * <p>Le jalon 3 garantit que le premier point est l'AOS, le dernier le LOS, et que
         * le sommet y figure. S'en servir donne des distances exactes aux trois instants
         * sans une seule propagation supplementaire ; les recalculer produirait des
         * valeurs legerement differentes de celles de la courbe affichee juste a cote.
         */
        static PassDto from(SatellitePass pass) {
            List<TrackPoint> track = pass.track();
            TrackPoint aos = track.getFirst();
            TrackPoint los = track.getLast();
            TrackPoint culmination = track.stream()
                    .filter(point -> point.instant().equals(pass.maxElevationTime()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "sommet absent de la trajectoire du passage de " + pass.aos()));

            return new PassDto(
                    PhaseDto.from(aos),
                    PhaseDto.from(culmination),
                    PhaseDto.from(los),
                    java.time.Duration.between(pass.aos(), pass.los()).toSeconds(),
                    track.stream().map(TrackPointDto::from).toList());
        }
    }

    public record PhaseDto(Instant instant,
                           double azimuthDeg,
                           double elevationDeg,
                           double rangeKm) {

        static PhaseDto from(TrackPoint point) {
            return new PhaseDto(point.instant(), point.azimuthDeg(),
                    point.elevationDeg(), point.rangeKm());
        }
    }

    public record TrackPointDto(Instant instant,
                                double azimuthDeg,
                                double elevationDeg,
                                double rangeKm,
                                SubPointDto subPoint,
                                boolean illuminated) {

        static TrackPointDto from(TrackPoint point) {
            return new TrackPointDto(point.instant(), point.azimuthDeg(), point.elevationDeg(),
                    point.rangeKm(), SubPointDto.from(point.subPoint()), point.illuminated());
        }
    }

    public record SubPointDto(double latitudeDeg, double longitudeDeg, double altitudeKm) {

        static SubPointDto from(SubSatellitePoint point) {
            return new SubPointDto(point.latitudeDeg(), point.longitudeDeg(), point.altitudeKm());
        }
    }
}
