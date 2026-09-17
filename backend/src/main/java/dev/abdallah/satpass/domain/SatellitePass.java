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
 * <p><b>The three remarkable points are points of the track, not a copy of them.</b> An
 * earlier shape carried seven scalars alongside the polyline — three instants and four
 * angles — which stated the same thing twice and left every reader to hope the two
 * agreed. Here {@code aos} is {@code track.getFirst()}, {@code los} is
 * {@code track.getLast()} and {@code culmination} is one of the points in between: the
 * invariant is an equality of objects checked at construction, so the three dates the
 * interface labels fall <em>on</em> the drawn curve by construction rather than to within
 * a tolerance. The side benefit is that the range and the sub-satellite point are
 * available at those three instants too, which the scalars did not carry.
 */
public record SatellitePass(TrackPoint aos,
                            TrackPoint culmination,
                            TrackPoint los,
                            List<TrackPoint> track) {

    public SatellitePass {
        if (aos == null || culmination == null || los == null) {
            throw new IllegalArgumentException("a pass needs its AOS, its culmination and its LOS");
        }
        if (track == null || track.size() < 2) {
            throw new IllegalArgumentException("a pass without a sampled track cannot be drawn");
        }
        // The defensive copy comes before the checks below: without it the caller could
        // empty the list once the record is built and the invariant would no longer hold.
        track = List.copyOf(track);
        if (!track.getFirst().equals(aos) || !track.getLast().equals(los)) {
            throw new IllegalArgumentException(
                    "the track must start at AOS (" + aos.instant() + ") and end at LOS ("
                            + los.instant() + "), it runs from " + track.getFirst().instant()
                            + " to " + track.getLast().instant());
        }
        if (!los.instant().isAfter(aos.instant())) {
            throw new IllegalArgumentException(
                    "LOS (" + los.instant() + ") must follow AOS (" + aos.instant() + ")");
        }
        // Containment, not an interval test: the culmination has to be a point that is
        // actually drawn, which is what lets the interface put its marker on the curve.
        if (!track.contains(culmination)) {
            throw new IllegalArgumentException(
                    "the culmination (" + culmination.instant() + ") is not a point of the track");
        }
    }

    public Duration duration() {
        return Duration.between(aos.instant(), los.instant());
    }
}
