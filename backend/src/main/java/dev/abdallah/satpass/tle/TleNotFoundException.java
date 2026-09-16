package dev.abdallah.satpass.tle;

/**
 * CelesTrak ne connait pas ce numero NORAD : numero invente, ou objet retire du
 * catalogue parce qu'il est rentre dans l'atmosphere.
 *
 * <p>Erreur <em>permanente</em>, contrairement a {@link TleUnavailableException}. C'est
 * ce qui justifie que le magasin oublie le satellite au lieu de continuer a servir son
 * dernier TLE connu : predire les passages d'un objet qui a fini de bruler serait pire
 * qu'une erreur franche.
 */
public class TleNotFoundException extends RuntimeException {

    private final int noradId;

    public TleNotFoundException(int noradId) {
        super("aucun TLE publie pour le satellite " + noradId);
        this.noradId = noradId;
    }

    public int noradId() {
        return noradId;
    }
}
