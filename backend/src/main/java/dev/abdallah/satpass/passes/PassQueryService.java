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
 * Assemble les deux moities du backend : le TLE du magasin et le calcul de passages.
 *
 * <p>C'est ici, et nulle part ailleurs, que les deux lignes d'un {@link TleSnapshot}
 * redeviennent un {@code TLE} d'Orekit. La couche web ne connait donc aucun type
 * d'Orekit, et le magasin n'en expose aucun : l'invariant du jalon 3 tient de bout en
 * bout.
 *
 * <p>La fenetre de recherche demarre a l'instant courant, lu une seule fois et transmis
 * dans le resultat. Relire l'horloge plus loin donnerait un debut de fenetre et un age
 * de TLE calcules a deux instants differents — un ecart minuscule, et un JSON qui ne se
 * recoupe pas.
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
