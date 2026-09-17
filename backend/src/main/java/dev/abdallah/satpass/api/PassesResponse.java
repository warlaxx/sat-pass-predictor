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
 * <p>The shape is the one documented in {@code docs/interface-mockup.html}, section
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
         * The three phases are the three remarkable points of the pass, which are also
         * points of its track.
         *
         * <p>Nothing is recomputed and nothing is searched for: the domain guarantees the
         * identity, so the labelled instants carry exactly the azimuth, elevation and
         * range of the curve drawn next to them. This method used to look the culmination
         * up in the track by date and throw when it did not find it; that failure mode no
         * longer exists.
         */
        static PassDto from(SatellitePass pass) {
            return new PassDto(
                    PhaseDto.from(pass.aos()),
                    PhaseDto.from(pass.culmination()),
                    PhaseDto.from(pass.los()),
                    pass.duration().toSeconds(),
                    pass.track().stream().map(TrackPointDto::from).toList());
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
