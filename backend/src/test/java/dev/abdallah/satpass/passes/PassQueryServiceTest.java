package dev.abdallah.satpass.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.abdallah.satpass.OrekitTest;
import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.TleSnapshot;
import dev.abdallah.satpass.tle.TleStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * La jonction entre le magasin de TLE et le calcul de passages.
 *
 * <p>Le point verifie ici n'est verifiable nulle part ailleurs : les <em>deux lignes
 * brutes</em> conservees par le domaine redonnent bien le meme TLE que celui qui a produit
 * la reference du jalon 2. C'est le prix a payer pour que le domaine n'expose aucun type
 * d'Orekit, et ce test est ce qui garantit qu'il est nul.
 */
@OrekitTest
class PassQueryServiceTest {

    /** Epoque du TLE de reference. La fenetre y demarre, la ou SGP4 est le plus fiable. */
    private static final Instant TLE_EPOCH = Instant.parse("2021-02-04T03:28:36.316Z");

    private static final ObserverLocation LYON = new ObserverLocation(45.7578, 4.8320, 170.0);

    @Autowired
    PassPredictionService predictionService;

    private PassQueryService serviceAt(Instant now, TleSnapshot snapshot) {
        TleStore store = mock(TleStore.class);
        when(store.get(25544)).thenReturn(snapshot);
        return new PassQueryService(store, predictionService, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static TleSnapshot referenceSnapshot(Instant fetchedAt) {
        return new TleSnapshot(25544, TleFixtures.issName(), TleFixtures.issLine1(),
                TleFixtures.issLine2(), TLE_EPOCH, fetchedAt, "celestrak");
    }

    @Test
    void rebuildsTheSameOrbitFromTheStoredLines() {
        PassQueryService service = serviceAt(TLE_EPOCH, referenceSnapshot(TLE_EPOCH));

        PassPrediction prediction =
                service.findPasses(25544, LYON, Duration.ofHours(24), 10.0);

        // Cinq passages sur 24 h, exactement comme la reference du jalon 2 : les deux
        // lignes brutes ont bien reconstruit le meme TLE.
        assertThat(prediction.passes()).hasSize(5);
        assertThat(prediction.passes().getFirst().aos())
                .isEqualTo(Instant.parse("2021-02-04T12:16:42.325291143Z"));
    }

    @Test
    void carriesTheTleThatWasActuallyUsed() {
        Instant fetchedAt = TLE_EPOCH.plus(Duration.ofHours(3));
        PassQueryService service = serviceAt(TLE_EPOCH.plus(Duration.ofHours(4)),
                referenceSnapshot(fetchedAt));

        PassPrediction prediction =
                service.findPasses(25544, LYON, Duration.ofHours(6), 10.0);

        assertThat(prediction.tle().fetchedAt()).isEqualTo(fetchedAt);
        assertThat(prediction.tle().line1()).isEqualTo(TleFixtures.issLine1());
        assertThat(prediction.minElevationDeg()).isEqualTo(10.0);
    }

    /**
     * L'instant du calcul est lu une seule fois et transmis. Sans cela, le debut de fenetre
     * et l'age du TLE affiche seraient calcules a deux instants differents.
     */
    @Test
    void readsTheClockOnlyOnceAndPassesItAlong() {
        Instant now = TLE_EPOCH.plus(Duration.ofHours(2));
        PassQueryService service = serviceAt(now, referenceSnapshot(TLE_EPOCH));

        PassPrediction prediction = service.findPasses(25544, LYON, Duration.ofHours(6), 10.0);

        assertThat(prediction.computedAt()).isEqualTo(now);
        assertThat(prediction.passes()).allSatisfy(pass ->
                assertThat(pass.aos()).isAfterOrEqualTo(now));
    }
}
