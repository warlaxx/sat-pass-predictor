package dev.abdallah.satpass.api;

import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.SubSatellitePoint;
import dev.abdallah.satpass.domain.TleSnapshot;
import dev.abdallah.satpass.domain.TrackPoint;
import dev.abdallah.satpass.passes.PassPrediction;
import java.time.Instant;
import java.util.List;

/**
 * A pass built by hand, without Orekit.
 *
 * <p>These tests are about the web layer: the shape of the JSON, the status codes, the
 * validation. Running a real propagation there would make them slow and would fail them
 * for reasons that are none of their business.
 *
 * <p>The pass still honours the {@link SatellitePass} invariants — the three remarkable
 * points are the very instances the track holds — because that identity is what the DTO
 * relies on to publish the three phases.
 */
final class PassFixtures {

    static final Instant AOS = Instant.parse("2026-09-22T19:18:54Z");
    static final Instant CULMINATION = Instant.parse("2026-09-22T19:22:16Z");
    static final Instant LOS = Instant.parse("2026-09-22T19:25:38Z");

    static final Instant TLE_EPOCH = Instant.parse("2026-09-15T04:12:33Z");
    static final Instant FETCHED_AT = Instant.parse("2026-09-16T11:25:04Z");
    static final Instant COMPUTED_AT = Instant.parse("2026-09-16T13:52:33Z");

    static final ObserverLocation LYON = new ObserverLocation(45.7578, 4.8320, 170.0);

    private static TrackPoint point(Instant instant, double azimuth, double elevation, double range) {
        return new TrackPoint(instant, azimuth, elevation, range,
                new SubSatellitePoint(38.71, -4.92, 419.6), instant.equals(CULMINATION), instant.equals(CULMINATION));
    }

    static SatellitePass pass() {
        TrackPoint aos = point(AOS, 292.5, 10.0, 1553.2);
        TrackPoint culmination = point(CULMINATION, 22.5, 63.1, 462.7);
        TrackPoint los = point(LOS, 112.4, 10.0, 1551.8);

        return new SatellitePass(aos, culmination, los, List.of(aos, culmination, los));
    }

    static TleSnapshot snapshot() {
        return new TleSnapshot(25544, "ISS (ZARYA)", TleFixtures.issLine1(), TleFixtures.issLine2(),
                TLE_EPOCH, FETCHED_AT, "celestrak");
    }

    static PassPrediction prediction() {
        return new PassPrediction(snapshot(), LYON, 10.0, COMPUTED_AT, List.of(pass()));
    }

    private PassFixtures() {
    }
}
