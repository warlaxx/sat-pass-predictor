package dev.abdallah.satpass.passes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.TrackPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.propagation.analytical.tle.TLE;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Jalon 3 — la trajectoire echantillonnee.
 *
 * <p>Ces tests portent sur la <em>forme</em> de la polyligne, pas sur des valeurs figees :
 * la justesse physique des positions est etablie ailleurs, par la reference du jalon 2 et
 * sa validation Skyfield. Ce qui est verifie ici est ce dont l'interface depend et qu'un
 * refactoring casserait sans bruit — que la courbe commence a l'AOS, finisse au LOS,
 * passe par le sommet, et que le pas annonce soit celui applique.
 */
@SpringBootTest
class TrackSamplingTest {

    private static final ObserverLocation LYON = new ObserverLocation(45.7578, 4.8320, 170.0);
    private static final double MIN_ELEVATION_DEG = 10.0;

    /** Doit rester egal a {@code PassPredictionService.TRACK_STEP_SECONDS}. */
    private static final long TRACK_STEP_SECONDS = 10L;

    /**
     * Tolerance angulaire, en degres.
     *
     * <p>Calibree sur la precision de la recherche de racine (1 ms) et sur la vitesse
     * angulaire de l'ISS au voisinage de l'horizon, environ 0,1 degre par seconde :
     * l'ecart attendu est de l'ordre de 1e-4 degre. Un millieme de degre laisse un ordre
     * de grandeur de marge sans rien laisser passer de significatif.
     */
    private static final double ELEVATION_TOLERANCE_DEG = 1.0e-3;

    @Autowired
    PassPredictionService service;

    @Autowired
    DataContext dataContext;

    private List<SatellitePass> passes() {
        TLE iss = TleFixtures.iss();
        Instant epoch = iss.getDate().toInstant(dataContext.getTimeScales());
        return service.predictPasses(iss, LYON, epoch, Duration.ofHours(24), MIN_ELEVATION_DEG);
    }

    /**
     * Critere de sortie du jalon 3 : la polyligne couvre exactement le passage, et ses
     * deux extremites sont au seuil d'elevation demande.
     */
    @Test
    void theTrackSpansExactlyThePassAndEndsAtTheElevationThreshold() {
        assertThat(passes()).isNotEmpty().allSatisfy(pass -> {
            TrackPoint first = pass.track().getFirst();
            TrackPoint last = pass.track().getLast();

            assertThat(first.instant()).as("premier point = AOS").isEqualTo(pass.aos());
            assertThat(last.instant()).as("dernier point = LOS").isEqualTo(pass.los());

            assertThat(first.elevationDeg())
                    .as("elevation a l'AOS")
                    .isCloseTo(MIN_ELEVATION_DEG, within(ELEVATION_TOLERANCE_DEG));
            assertThat(last.elevationDeg())
                    .as("elevation au LOS")
                    .isCloseTo(MIN_ELEVATION_DEG, within(ELEVATION_TOLERANCE_DEG));
        });
    }

    /**
     * Le sommet tombe sur la courbe.
     *
     * <p>Sans cette garantie, l'interface dessinerait le marqueur du sommet a cote de la
     * trajectoire : pres du zenith l'ISS gagne plusieurs degres d'elevation en quelques
     * secondes, et le sommet, trouve par annulation de la derivee, ne tombe pas sur un
     * multiple de {@value #TRACK_STEP_SECONDS} s. C'est un defaut visible a l'oeil, donc
     * un test.
     */
    @Test
    void theCulminationIsOneOfTheTrackPoints() {
        assertThat(passes()).allSatisfy(pass -> {
            TrackPoint apex = pass.track().stream()
                    .filter(point -> point.instant().equals(pass.maxElevationTime()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "le sommet " + pass.maxElevationTime() + " ne figure pas dans la trajectoire"));

            assertThat(apex.elevationDeg())
                    .as("le sommet de la courbe est le maximum annonce")
                    .isCloseTo(pass.maxElevationDeg(), within(ELEVATION_TOLERANCE_DEG));

            assertThat(pass.track())
                    .as("aucun point de la trajectoire ne depasse ce maximum")
                    .allSatisfy(point -> assertThat(point.elevationDeg())
                            .isLessThanOrEqualTo(pass.maxElevationDeg() + ELEVATION_TOLERANCE_DEG));
        });
    }

    /**
     * Le pas applique est celui annonce : points chronologiques, jamais separes de plus du
     * pas nominal, jamais confondus.
     *
     * <p>Deux points a quelques microsecondes d'intervalle ne sont pas un detail
     * esthetique : un segment de longueur nulle dans une polyligne SVG produit des
     * artefacts de jointure, et la lecture continue de la barre de transport sauterait.
     */
    @Test
    void samplesAreChronologicalAndRegularlySpaced() {
        assertThat(passes()).allSatisfy(pass -> {
            List<TrackPoint> track = pass.track();
            for (int i = 1; i < track.size(); i++) {
                Duration gap = Duration.between(track.get(i - 1).instant(), track.get(i).instant());
                assertThat(gap)
                        .as("intervalle entre les points %d et %d", i - 1, i)
                        .isGreaterThan(Duration.ofMillis(1))
                        .isLessThanOrEqualTo(Duration.ofSeconds(TRACK_STEP_SECONDS));
            }
        });
    }

    /**
     * Le nombre de points suit la duree du passage, puisque le pas est fixe. C'est la
     * contrepartie assumee du choix : on la teste plutot que de la subir.
     */
    @Test
    void theNumberOfSamplesFollowsTheDurationOfThePass() {
        assertThat(passes()).allSatisfy(pass -> {
            long gridPoints = pass.duration().toSeconds() / TRACK_STEP_SECONDS;
            // Grille interieure, plus AOS, sommet et LOS, moins les doublons ecartes :
            // on encadre largement plutot que de reproduire l'arithmetique du service ici.
            assertThat(pass.track().size()).isBetween((int) gridPoints, (int) gridPoints + 4);
        });
    }

    /**
     * Le point sous-satellite est plausible : une orbite basse, ni un point au sol ni un
     * satellite geostationnaire. Une confusion de repere — ITRF pris pour TEME — se
     * verrait ici, sous la forme d'une latitude depassant l'inclinaison de l'orbite.
     */
    @Test
    void everySampleCarriesAPlausibleSubSatellitePoint() {
        assertThat(passes()).allSatisfy(pass -> assertThat(pass.track()).allSatisfy(point -> {
            assertThat(point.subPoint().altitudeKm())
                    .as("altitude de l'ISS")
                    .isBetween(300.0, 500.0);
            assertThat(point.subPoint().latitudeDeg())
                    .as("l'ISS ne depasse pas l'inclinaison de son orbite, 51,6 degres")
                    .isBetween(-52.0, 52.0);
            assertThat(point.subPoint().longitudeDeg()).isBetween(-180.0, 180.0);

            // A 10 degres d'elevation l'ISS est a environ 1 500 km ; au zenith, a son
            // altitude. Hors de cet intervalle, la geometrie du passage est fausse.
            assertThat(point.rangeKm()).isBetween(300.0, 1800.0);

            assertThat(point.illuminated())
                    .as("le calcul d'eclipse arrive au jalon 10 ; d'ici la, le champ est neutre")
                    .isFalse();
        }));
    }

    /** L'invariant du domaine est verifie a la construction, pas seulement documente. */
    @Test
    void aPassCannotBeBuiltWithATrackThatDoesNotMatchItsBounds() {
        SatellitePass pass = passes().getFirst();
        List<TrackPoint> amputated = pass.track().subList(1, pass.track().size());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SatellitePass(
                        pass.aos(), pass.aosAzimuthDeg(),
                        pass.maxElevationTime(), pass.maxElevationDeg(), pass.maxElevationAzimuthDeg(),
                        pass.los(), pass.losAzimuthDeg(),
                        amputated))
                .withMessageContaining("AOS");
    }
}
