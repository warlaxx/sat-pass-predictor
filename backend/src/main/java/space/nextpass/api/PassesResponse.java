package space.nextpass.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import space.nextpass.domain.ObserverLocation;
import space.nextpass.domain.SatellitePass;
import space.nextpass.domain.SubSatellitePoint;
import space.nextpass.domain.TrackPoint;
import space.nextpass.passes.PassPrediction;
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
 *
 * <h2>Doppler</h2>
 * Every point carries its range rate, which is a property of the geometry alone. The
 * Doppler shift is that rate scaled by a carrier frequency, so it is only published when
 * the caller names one ({@code frequencyMhz}); otherwise the field is {@code null}, and
 * the key stays in the JSON so the shape of the response never depends on a parameter.
 * The scaling happens here, at the edge, rather than in the computation: the cached
 * prediction stays one answer for every frequency, and a radio amateur tuning three
 * transponders on the same pass costs one propagation, not three.
 */
public record PassesResponse(SatelliteDto satellite,
                             TleDto tle,
                             ObserverDto observer,
                             double minElevationDeg,
                             Double frequencyMhz,
                             Instant computedAt,
                             List<PassDto> passes) {

    /** Speed of light in vacuum, in km/s — exact by definition of the metre. */
    private static final double SPEED_OF_LIGHT_KM_S = 299_792.458;

    /**
     * @param frequencyMhz the carrier frequency the Doppler shift is computed for, or
     *                     {@code null} to publish the range rate alone
     */
    public static PassesResponse from(PassPrediction prediction, Double frequencyMhz) {
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
                ObserverDto.from(observer),
                prediction.minElevationDeg(),
                frequencyMhz,
                prediction.computedAt(),
                prediction.passes().stream().map(pass -> PassDto.from(pass, frequencyMhz)).toList());
    }

    /**
     * First-order Doppler shift, in hertz, of a carrier emitted by the satellite and
     * received by the observer: {@code -f · ṙ / c}. Approaching (negative range rate)
     * raises the received frequency.
     *
     * <p>First order is not a shortcut: the next term, {@code (v/c)²}, is about 7e-10 in
     * low Earth orbit — a tenth of a hertz at 145 MHz, below what any receiver resolves
     * and far below the error the age of the TLE puts on the range rate itself. For an
     * uplink, the correction to apply to the transmitter is the opposite sign.
     */
    static Double shiftHz(double rangeRateKmS, Double frequencyMhz) {
        if (frequencyMhz == null) {
            return null;
        }
        return -frequencyMhz * 1.0e6 * rangeRateKmS / SPEED_OF_LIGHT_KM_S;
    }

    /**
     * The same answer with every pass reduced to its three phases. A batch of twenty-five
     * predictions over ten days carries tens of thousands of track points; a caller
     * ranking passes or scheduling a station needs none of them.
     */
    public PassesResponse withoutTracks() {
        return new PassesResponse(satellite, tle, observer, minElevationDeg, frequencyMhz, computedAt,
                passes.stream()
                        .map(pass -> new PassDto(pass.aos(), pass.culmination(), pass.los(),
                                pass.durationSeconds(), List.of()))
                        .toList());
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

        static ObserverDto from(ObserverLocation observer) {
            return new ObserverDto(observer.latitudeDeg(), observer.longitudeDeg(), observer.altitudeMeters());
        }
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
        static PassDto from(SatellitePass pass, Double frequencyMhz) {
            return new PassDto(
                    PhaseDto.from(pass.aos(), frequencyMhz),
                    PhaseDto.from(pass.culmination(), frequencyMhz),
                    PhaseDto.from(pass.los(), frequencyMhz),
                    pass.duration().toSeconds(),
                    pass.track().stream().map(point -> TrackPointDto.from(point, frequencyMhz)).toList());
        }
    }

    public record PhaseDto(Instant instant,
                           double azimuthDeg,
                           double elevationDeg,
                           double rangeKm,
                           double rangeRateKmS,
                           Double dopplerHz) {

        static PhaseDto from(TrackPoint point, Double frequencyMhz) {
            return new PhaseDto(point.instant(), point.azimuthDeg(),
                    point.elevationDeg(), point.rangeKm(), point.rangeRateKmS(),
                    shiftHz(point.rangeRateKmS(), frequencyMhz));
        }
    }

    public record TrackPointDto(Instant instant,
                                double azimuthDeg,
                                double elevationDeg,
                                double rangeKm,
                                double rangeRateKmS,
                                Double dopplerHz,
                                SubPointDto subPoint,
                                boolean illuminated,
                                boolean visible,
                                // ABD-35: left out when in shadow or for a satellite
                                // without an established standard magnitude.
                                @JsonInclude(JsonInclude.Include.NON_NULL) Double magnitude) {

        static TrackPointDto from(TrackPoint point, Double frequencyMhz) {
            return new TrackPointDto(point.instant(), point.azimuthDeg(), point.elevationDeg(),
                    point.rangeKm(), point.rangeRateKmS(), shiftHz(point.rangeRateKmS(), frequencyMhz),
                    SubPointDto.from(point.subPoint()), point.illuminated(), point.visible(),
                    point.magnitude() == null ? null : Math.round(point.magnitude() * 10.0) / 10.0);
        }
    }

    public record SubPointDto(double latitudeDeg, double longitudeDeg, double altitudeKm) {

        static SubPointDto from(SubSatellitePoint point) {
            return new SubPointDto(point.latitudeDeg(), point.longitudeDeg(), point.altitudeKm());
        }
    }
}
