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
 * Un passage fabrique a la main, sans Orekit.
 *
 * <p>Ces tests portent sur la couche web : la forme du JSON, les codes HTTP, la
 * validation. Y faire tourner une propagation reelle les rendrait lents et les ferait
 * echouer pour des raisons qui ne les concernent pas.
 *
 * <p>Le passage respecte quand meme les invariants de {@link SatellitePass} — premier
 * point a l'AOS, dernier au LOS, sommet present — parce que la construction du DTO s'en
 * sert pour prelever les trois phases.
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
                new SubSatellitePoint(38.71, -4.92, 419.6), false);
    }

    static SatellitePass pass() {
        return new SatellitePass(
                AOS, 292.5,
                CULMINATION, 63.1, 22.5,
                LOS, 112.4,
                List.of(
                        point(AOS, 292.5, 10.0, 1553.2),
                        point(CULMINATION, 22.5, 63.1, 462.7),
                        point(LOS, 112.4, 10.0, 1551.8)));
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
