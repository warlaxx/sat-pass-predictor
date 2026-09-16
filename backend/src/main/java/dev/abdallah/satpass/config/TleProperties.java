package dev.abdallah.satpass.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reglages de la recuperation des TLE.
 *
 * @param baseUrl        racine de l'API GP de CelesTrak.
 * @param connectTimeout delai d'etablissement de la connexion.
 * @param readTimeout    delai de lecture de la reponse.
 * @param refreshAfter   au-dela de cet age <em>depuis la derniere recuperation</em>, on
 *                       retente CelesTrak. Ce n'est pas une duree de validite : si
 *                       l'appel echoue, le snapshot precedent reste servi.
 * @param maxAge         au-dela de cet age <em>depuis l'epoque des elements</em>, on
 *                       refuse de predire. C'est la seule limite dure, et elle porte sur
 *                       la physique, pas sur le reseau.
 * @param maximumSize    nombre de satellites gardes en memoire. Borne le magasin, qui
 *                       n'a par ailleurs aucune expiration.
 */
@ConfigurationProperties("tle")
public record TleProperties(String baseUrl,
                            Duration connectTimeout,
                            Duration readTimeout,
                            Duration refreshAfter,
                            Duration maxAge,
                            int maximumSize) {

    public TleProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("tle.base-url manquante");
        }
        if (refreshAfter == null || refreshAfter.isNegative() || refreshAfter.isZero()) {
            throw new IllegalArgumentException("tle.refresh-after doit etre strictement positive");
        }
        if (maxAge == null || maxAge.compareTo(refreshAfter) <= 0) {
            throw new IllegalArgumentException(
                    "tle.max-age doit depasser tle.refresh-after, sinon un TLE serait rejete"
                            + " avant meme d'avoir eu une chance d'etre rafraichi");
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("tle.maximum-size doit etre strictement positive");
        }
    }
}
