package dev.abdallah.satpass;

import dev.abdallah.satpass.validation.ValidationReference;
import org.orekit.propagation.analytical.tle.TLE;

/**
 * Jeux d'elements orbitaux figes pour les tests.
 *
 * <p>Un TLE fige, jamais telecharge : un test qui depend du reseau n'est pas un test,
 * c'est une alerte de supervision deguisee. Il echouerait un jour ou CelesTrak est
 * lent, et ses resultats changeraient a chaque execution puisque les TLE sont
 * republies plusieurs fois par jour.
 */
public final class TleFixtures {

    /**
     * ISS (NORAD 25544), epoque 2021-02-04T03:28:36.316 UTC.
     *
     * <p>Lu depuis {@code validation/iss-lyon-reference.json} plutot que recopie ici :
     * le meme TLE sert au test de non-regression Java et au script de validation
     * Python, et deux copies finiraient par diverger. Le TLE provient du jeu de tests
     * d'Orekit, donc d'un TLE reellement publie, sommes de controle valides.
     */
    public static TLE iss() {
        return ValidationReference.load().tle();
    }

    private TleFixtures() {
    }
}
