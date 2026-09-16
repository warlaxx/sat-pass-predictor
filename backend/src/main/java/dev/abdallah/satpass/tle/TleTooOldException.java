package dev.abdallah.satpass.tle;

import java.time.Duration;

/**
 * Le seul TLE disponible a une epoque trop ancienne pour qu'une prediction ait un sens.
 *
 * <p>La degradation propre a une limite. SGP4 diverge d'environ un kilometre par jour
 * en orbite basse ; passe une semaine, l'erreur le long de la trajectoire se compte en
 * minutes sur l'heure de passage, et afficher une courbe au degre pres serait une
 * fausse precision. Mieux vaut dire qu'on ne sait pas.
 */
public class TleTooOldException extends RuntimeException {

    public TleTooOldException(int noradId, Duration age, Duration maxAge) {
        super("TLE du satellite " + noradId + " age de " + age.toHours() + " h,"
                + " au-dela de la limite de " + maxAge.toHours() + " h");
    }
}
