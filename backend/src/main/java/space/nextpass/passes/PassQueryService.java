package space.nextpass.passes;

import space.nextpass.domain.ObserverLocation;
import space.nextpass.domain.SatellitePass;
import space.nextpass.domain.TleSnapshot;
import space.nextpass.tle.TleStore;
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
 *
 * <p>The elements are read <em>before</em> the cache is consulted, and never after: they
 * are what decides whether a cached answer still holds, and the store is the only thing
 * here allowed to reach the network. A cache hit therefore still costs an in-memory
 * lookup and, at most once per satellite per {@code tle.refresh-after}, a fetch — which
 * is the call the persistent snapshot in {@code TleStore} is there to avoid.
 */
@Service
public class PassQueryService {

    private final TleStore tleStore;
    private final PassPredictionService predictionService;
    private final PredictionCache cache;
    private final Clock clock;

    public PassQueryService(TleStore tleStore,
                            PassPredictionService predictionService,
                            PredictionCache cache,
                            Clock clock) {
        this.tleStore = tleStore;
        this.predictionService = predictionService;
        this.cache = cache;
        this.clock = clock;
    }

    public PassPrediction findPasses(int noradId,
                                     ObserverLocation observer,
                                     Duration window,
                                     double minElevationDeg) {
        return findPassesForSites(noradId, List.of(observer), window, minElevationDeg).getFirst();
    }

    /**
     * The same query for several sites at once, which is what a batch asks of one
     * satellite.
     *
     * <p>The elements and the clock are read once for all the sites, not once per site:
     * every prediction in the list is computed from the same TLE over the same window, so
     * two sites of one batch can be compared without asking whether the elements changed
     * between them. A missing satellite also costs one catalogue lookup, not one per site
     * — the store forgets an object the catalogue does not have, and asking again for the
     * next site would ask CelesTrak again.
     */
    public List<PassPrediction> findPassesForSites(int noradId,
                                                   List<ObserverLocation> observers,
                                                   Duration window,
                                                   double minElevationDeg) {
        TleSnapshot snapshot = tleStore.get(noradId);
        Instant computedAt = clock.instant();

        return observers.stream()
                .map(observer -> cache.get(noradId, observer, window, minElevationDeg, snapshot, computedAt,
                        () -> compute(snapshot, observer, window, minElevationDeg, computedAt)))
                .toList();
    }

    private PassPrediction compute(TleSnapshot snapshot,
                                   ObserverLocation observer,
                                   Duration window,
                                   double minElevationDeg,
                                   Instant computedAt) {
        TLE tle = new TLE(snapshot.line1(), snapshot.line2());
        List<SatellitePass> passes =
                predictionService.predictPasses(tle, observer, computedAt, window, minElevationDeg);
        return new PassPrediction(snapshot, observer, minElevationDeg, computedAt, passes);
    }
}
