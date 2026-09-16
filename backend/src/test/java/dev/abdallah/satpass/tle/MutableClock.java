package dev.abdallah.satpass.tle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Horloge que le test avance a la main.
 *
 * <p>Les regles a verifier ici — « retenter au-dela de deux heures », « refuser au-dela
 * de sept jours » — sont des regles sur le temps. Les tester avec l'heure murale
 * reviendrait a attendre, ou a baisser les seuils jusqu'a ne plus tester les vrais.
 */
final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant start) {
        this.instant = start;
    }

    void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
