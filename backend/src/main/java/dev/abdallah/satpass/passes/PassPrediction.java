package dev.abdallah.satpass.passes;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * Resultat complet d'une interrogation : les passages, et tout ce qu'il faut pour les
 * juger.
 *
 * <p>Le TLE utilise voyage avec les passages plutot que d'etre relu plus tard. Deux
 * raisons : l'age affiche doit etre celui du TLE qui a <em>reellement</em> servi au
 * calcul, et {@code computedAt} fige l'instant de debut de fenetre, sans quoi deux
 * lectures de l'horloge dans la meme requete pourraient ne pas coincider.
 */
public record PassPrediction(TleSnapshot tle,
                             ObserverLocation observer,
                             double minElevationDeg,
                             Instant computedAt,
                             List<SatellitePass> passes) {

    public PassPrediction {
        passes = List.copyOf(passes);
    }
}
