package dev.abdallah.satpass.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A visible pass: the stretch of track during which the satellite stays above the
 * minimum elevation the observer asked for.
 *
 * <p>Every date is a UTC {@link Instant}. Time zones are a display problem, never a
 * computation problem: they enter the project only at the frontend.
 *
 * <p>Azimuths are in degrees in [0, 360), measured from North towards East (the
 * convention of Orekit's {@code TopocentricFrame}). AOS stands for <i>acquisition of
 * signal</i>, LOS for <i>loss of signal</i>: ground-station vocabulary, used here because
 * it is the vocabulary of the people this project is aimed at.
 *
 * <p>{@code track} is the same pass, sampled. The three instants above are what you read
 * in a table; {@code track} is what you draw. The first point is AOS, the last is LOS,
 * and the culmination is one of them: the three dates the interface labels therefore
 * fall <em>on</em> the curve, never beside it.
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
            throw new IllegalArgumentException("pass dates are missing");
        }
        if (!los.isAfter(aos)) {
            throw new IllegalArgumentException("LOS (" + los + ") must follow AOS (" + aos + ")");
        }
        if (maxElevationTime.isBefore(aos) || maxElevationTime.isAfter(los)) {
            throw new IllegalArgumentException(
                    "culmination (" + maxElevationTime + ") must fall between AOS and LOS");
        }
        if (track == null || track.size() < 2) {
            throw new IllegalArgumentException("a pass without a sampled track cannot be drawn");
        }
        // The defensive copy protects the invariant checked right after: without it, the
        // caller could empty the list once the record is built.
        track = List.copyOf(track);
        if (!track.getFirst().instant().equals(aos) || !track.getLast().instant().equals(los)) {
            throw new IllegalArgumentException(
                    "the track must start at AOS (" + aos + ") and end at LOS (" + los + "), "
                            + "it runs from " + track.getFirst().instant() + " to " + track.getLast().instant());
        }
    }

    public Duration duration() {
        return Duration.between(aos, los);
    }
}
