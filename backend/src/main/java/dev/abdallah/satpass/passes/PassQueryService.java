package dev.abdallah.satpass.passes;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.TleSnapshot;
import dev.abdallah.satpass.tle.TleStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.orekit.propagation.analytical.tle.TLE;
import org.springframework.stereotype.Service;

/**
 * Joins the two halves of the backend: the TLE from the store and the pass computation.
 *
 * <p>This is where, and nowhere else, the two lines of a {@link TleSnapshot} become an
 * Orekit {@code TLE} again. The web layer therefore knows no Orekit type, and the store
 * exposes none: the milestone 3 invariant holds end to end.
 *
 * <p>The search window starts at the current instant, read once and carried into the
 * result. Reading the clock again further down would compute the start of the window and
 * the age of the TLE at two different instants — a minuscule gap, and a JSON response
 * that does not add up.
 */
@Service
public class PassQueryService {

    private final TleStore tleStore;
    private final PassPredictionService predictionService;
    private final Clock clock;

    public PassQueryService(TleStore tleStore,
                            PassPredictionService predictionService,
                            Clock clock) {
        this.tleStore = tleStore;
        this.predictionService = predictionService;
        this.clock = clock;
    }

    public PassPrediction findPasses(int noradId,
                                     ObserverLocation observer,
                                     Duration window,
                                     double minElevationDeg) {
        TleSnapshot snapshot = tleStore.get(noradId);
        Instant computedAt = clock.instant();

        TLE tle = new TLE(snapshot.line1(), snapshot.line2());
        List<SatellitePass> passes =
                predictionService.predictPasses(tle, observer, computedAt, window, minElevationDeg);

        return new PassPrediction(snapshot, observer, minElevationDeg, computedAt, passes);
    }
}
