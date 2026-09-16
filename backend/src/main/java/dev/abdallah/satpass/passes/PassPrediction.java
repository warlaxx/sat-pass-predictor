package dev.abdallah.satpass.passes;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * The complete result of a query: the passes, and everything needed to judge them.
 *
 * <p>The TLE that was used travels with the passes rather than being read again later.
 * Two reasons: the age on display must be that of the TLE which <em>actually</em> served
 * the computation, and {@code computedAt} pins the start of the window, without which two
 * readings of the clock in the same request could disagree.
 */
public record PassPrediction(TleSnapshot tle,
                             ObserverLocation observer,
                             double minElevationDeg,
                             Instant computedAt,
                             List<SatellitePass> passes) {

    public PassPrediction {
        passes = List.copyOf(passes);
    }
}
