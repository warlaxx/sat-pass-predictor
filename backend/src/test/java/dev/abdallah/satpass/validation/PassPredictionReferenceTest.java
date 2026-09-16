package dev.abdallah.satpass.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.passes.PassPredictionService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.propagation.analytical.tle.TLE;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Jalon 2 — non-regression contre la reference validee.
 *
 * <h2>Ce que ce test prouve, et ce qu'il ne prouve pas</h2>
 *
 * <p>Il prouve qu'Orekit reproduit aujourd'hui ce qu'il produisait le jour ou la
 * reference a ete figee. Il ne prouve rien, a lui seul, sur la justesse physique de
 * cette reference : un calcul faux fige une reference fausse, que ce test defendrait
 * ensuite fidelement.
 *
 * <p>C'est {@code scripts/validate-against-skyfield.py} qui etablit la justesse, en
 * confrontant le meme fichier a Skyfield — une implementation de SGP4 independante,
 * ecrite en Python, sans lien de code avec Orekit. Les deux controles sont
 * complementaires et aucun ne remplace l'autre : l'un surveille la derive, l'autre
 * ancre la verite.
 *
 * <h2>Sur les tolerances</h2>
 *
 * <p>Elles ne sont pas des marges d'erreur physiques. L'accord mesure entre Orekit et
 * Skyfield est de 0,53 millidegre en elevation et 2,0 millidegres en azimut. Les
 * tolerances ci-dessous, mille fois plus larges, absorbent une evolution interne
 * d'Orekit ou un rafraichissement du jeu de donnees — pas une erreur de modele, qui
 * serait de plusieurs ordres de grandeur superieure et ferait echouer le test.
 *
 * <p>Calibration utile pour lire un echec : au voisinage de l'AOS, l'ISS gagne environ
 * 0,1 degre d'elevation par seconde. Un ecart d'une seconde et un ecart de 0,1 degre
 * decrivent donc le meme evenement.
 */
@SpringBootTest
class PassPredictionReferenceTest {

    /** Derive temporelle toleree sur AOS, sommet et LOS. */
    private static final ChronoUnit TIME_UNIT = ChronoUnit.SECONDS;
    private static final long TIME_TOLERANCE = 1L;

    /** Derive angulaire toleree, en degres, sur les elevations et les azimuts. */
    private static final double ANGLE_TOLERANCE_DEG = 0.1;

    @Autowired
    PassPredictionService service;

    @Autowired
    DataContext dataContext;

    @Test
    void orekitReproducesTheValidatedReference() {
        ValidationReference reference = ValidationReference.load();
        TLE tle = reference.tle();

        assertThat(reference.window().startsAtTleEpoch())
                .as("cette reference suppose une fenetre demarrant a l'epoque du TLE")
                .isTrue();
        Instant start = tle.getDate().toInstant(dataContext.getTimeScales());

        List<SatellitePass> computed = service.predictPasses(
                tle,
                reference.observerLocation(),
                start,
                reference.windowDuration(),
                reference.minElevationDeg());

        assertThat(computed)
                .as("nombre de passages")
                .hasSameSizeAs(reference.passes());

        for (int i = 0; i < computed.size(); i++) {
            SatellitePass actual = computed.get(i);
            ValidationReference.ExpectedPass expected = reference.passes().get(i);

            assertThat(actual.aos())
                    .as("passage %d : AOS", i + 1)
                    .isCloseTo(expected.aos(), within(TIME_TOLERANCE, TIME_UNIT));
            assertThat(actual.maxElevationTime())
                    .as("passage %d : heure du sommet", i + 1)
                    .isCloseTo(expected.maxElevationTime(), within(TIME_TOLERANCE, TIME_UNIT));
            assertThat(actual.los())
                    .as("passage %d : LOS", i + 1)
                    .isCloseTo(expected.los(), within(TIME_TOLERANCE, TIME_UNIT));

            assertThat(actual.maxElevationDeg())
                    .as("passage %d : elevation maximale", i + 1)
                    .isCloseTo(expected.maxElevationDeg(), within(ANGLE_TOLERANCE_DEG));
            assertThat(actual.aosAzimuthDeg())
                    .as("passage %d : azimut a l'AOS", i + 1)
                    .isCloseTo(expected.aosAzimuthDeg(), within(ANGLE_TOLERANCE_DEG));
            assertThat(actual.maxElevationAzimuthDeg())
                    .as("passage %d : azimut au sommet", i + 1)
                    .isCloseTo(expected.maxElevationAzimuthDeg(), within(ANGLE_TOLERANCE_DEG));
            assertThat(actual.losAzimuthDeg())
                    .as("passage %d : azimut au LOS", i + 1)
                    .isCloseTo(expected.losAzimuthDeg(), within(ANGLE_TOLERANCE_DEG));
        }
    }

    /**
     * Le fichier de reference est partage avec le script Python. Si sa forme change sans
     * que le script suive, les deux controles cessent silencieusement de porter sur la
     * meme chose — ce test rend la rupture bruyante.
     */
    @Test
    void theSharedReferenceIsCompleteAndSelfConsistent() {
        ValidationReference reference = ValidationReference.load();

        assertThat(reference.satellite().tleLine1()).startsWith("1 ");
        assertThat(reference.satellite().tleLine2()).startsWith("2 ");
        assertThat(reference.minElevationDeg()).isBetween(0.0, 90.0);
        assertThat(reference.window().hours()).isPositive();
        assertThat(reference.passes()).isNotEmpty();

        assertThat(reference.passes()).allSatisfy(pass -> {
            assertThat(pass.aos()).isBefore(pass.maxElevationTime());
            assertThat(pass.maxElevationTime()).isBefore(pass.los());
            assertThat(pass.maxElevationDeg()).isGreaterThanOrEqualTo(reference.minElevationDeg());
        });
    }
}
