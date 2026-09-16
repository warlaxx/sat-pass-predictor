package dev.abdallah.satpass.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Un TLE tel qu'il a ete recupere, avec de quoi juger de sa fraicheur.
 *
 * <h2>Pourquoi les deux lignes brutes et pas un {@code TLE} d'Orekit</h2>
 * Le domaine n'importe aucun type d'Orekit (decide au jalon 3). Les deux lignes sont
 * de toute facon la forme canonique : c'est ce que CelesTrak publie, c'est ce que le
 * JSON de l'API renvoie, et c'est ce qu'un utilisateur recopie pour verifier ailleurs.
 * Le {@code TLE} d'Orekit est reconstruit a la volee dans la couche de propagation ;
 * le parsing coute quelques microsecondes, negligeable devant une propagation SGP4 sur
 * 24 h.
 *
 * <h2>Deux ages, deux usages</h2>
 * <ul>
 *   <li>{@link #ageSinceEpoch} — le temps ecoule depuis l'epoque des elements. C'est
 *       <em>l'age physique</em> : l'erreur de SGP4 croit avec lui, de l'ordre du
 *       kilometre par jour en orbite basse. C'est ce que le bandeau d'incertitude de
 *       l'interface affiche.</li>
 *   <li>{@link #ageSinceFetch} — le temps ecoule depuis l'appel reseau. C'est
 *       <em>l'age operationnel</em> : il decide seulement s'il faut retenter CelesTrak.
 *       Un TLE recupere il y a une minute peut tres bien avoir une epoque vieille de
 *       deux jours si le satellite n'a pas ete re-observe.</li>
 * </ul>
 * Les confondre est l'erreur classique : un cache qui expire au bout de deux heures
 * croit garantir une precision qu'il ne controle pas.
 */
public record TleSnapshot(int noradId,
                          String name,
                          String line1,
                          String line2,
                          Instant epoch,
                          Instant fetchedAt,
                          String source) {

    /** Longueur d'une ligne de TLE, fixee par le format a colonnes de la NORAD. */
    private static final int LINE_LENGTH = 69;

    public TleSnapshot {
        if (noradId <= 0) {
            throw new IllegalArgumentException("numero NORAD invalide : " + noradId);
        }
        requireTleLine(line1, 1);
        requireTleLine(line2, 2);
        if (epoch == null) {
            throw new IllegalArgumentException("epoque manquante");
        }
        if (fetchedAt == null) {
            throw new IllegalArgumentException("date de recuperation manquante");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("nom de satellite manquant");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source manquante");
        }
    }

    private static void requireTleLine(String line, int expectedNumber) {
        if (line == null || line.length() != LINE_LENGTH) {
            throw new IllegalArgumentException(
                    "ligne " + expectedNumber + " : " + LINE_LENGTH + " caracteres attendus, recu "
                            + (line == null ? "null" : line.length()));
        }
        if (line.charAt(0) != (char) ('0' + expectedNumber)) {
            throw new IllegalArgumentException(
                    "ligne " + expectedNumber + " : ne commence pas par '" + expectedNumber + "'");
        }
    }

    /** Age physique des elements : duree ecoulee depuis leur epoque. Peut etre negatif. */
    public Duration ageSinceEpoch(Instant now) {
        return Duration.between(epoch, now);
    }

    /** Age operationnel : duree ecoulee depuis l'appel reseau qui a produit ce snapshot. */
    public Duration ageSinceFetch(Instant now) {
        return Duration.between(fetchedAt, now);
    }
}
