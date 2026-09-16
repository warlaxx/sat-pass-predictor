package dev.abdallah.satpass.api;

import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.SubSatellitePoint;
import dev.abdallah.satpass.domain.TrackPoint;
import dev.abdallah.satpass.passes.PassPrediction;
import java.time.Instant;
import java.util.List;

/**
 * The whole API contract, in one file.
 *
 * <p>These types are deliberately kept apart from the domain. A domain record changes
 * when the physics or the computation demands it; a DTO changes when the client demands
 * it. Merging the two means publishing every internal refactoring as a breaking API
 * change — and never being able to rename a domain field because a browser reads it.
 *
 * <p>The shape is the one documented in {@code docs/maquette-interface.html}, section
 * "What the API must return". It was frozen before the frontend was written, so that the
 * frontend has nothing to negotiate.
 *
 * <p>Every date is a UTC {@link Instant}, serialised as ISO-8601. The user's time zone is
 * a display problem: solving it here would force the server to know the browser, and
 * would make two identical responses incomparable.
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
     * The age is computed here, once, rather than left to the client.
     *
     * <p>The interface's uncertainty banner depends on it, and a client recomputing it
     * from {@code epoch} and its own clock would show a wrong age as soon as that clock
     * drifts. The two raw lines come along: they make the response checkable elsewhere,
     * without which nothing lets anyone verify the computation.
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
         * The three phases are <em>read out of the track</em>, not recomputed.
         *
         * <p>Milestone 3 guarantees that the first point is AOS, the last is LOS, and the
         * culmination is among them. Using them gives exact ranges at the three instants
         * without a single extra propagation; recomputing would produce values slightly
         * different from the curve drawn right next to them.
         */
        static PassDto from(SatellitePass pass) {
            List<TrackPoint> track = pass.track();
            TrackPoint aos = track.getFirst();
            TrackPoint los = track.getLast();
            TrackPoint culmination = track.stream()
                    .filter(point -> point.instant().equals(pass.maxElevationTime()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "culmination missing from the track of the pass starting at " + pass.aos()));

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
