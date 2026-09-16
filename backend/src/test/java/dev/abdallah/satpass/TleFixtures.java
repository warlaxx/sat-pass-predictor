package dev.abdallah.satpass;

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
     * <p>Provient du jeu de tests d'Orekit lui-meme, donc d'un TLE reellement publie :
     * sommes de controle valides, valeurs coherentes. Son age est sans importance ici,
     * puisque toutes les fenetres de test partent de son epoque.
     */
    public static TLE iss() {
        return new TLE(
                "1 25544U 98067A   21035.14486477  .00001026  00000-0  26816-4 0  9998",
                "2 25544  51.6455 280.7636 0002243 335.6496 186.1723 15.48938788267977");
    }

    private TleFixtures() {
    }
}
