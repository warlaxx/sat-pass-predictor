package dev.abdallah.satpass.tle;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.abdallah.satpass.config.TleProperties;
import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Point d'acces unique aux TLE : dernier element connu par satellite, rafraichi
 * a la demande.
 *
 * <h2>Pourquoi ce n'est pas un cache a TTL</h2>
 * La roadmap disait « cache Caffeine, TTL 2 h », et exigeait deux lignes plus bas qu'un
 * CelesTrak injoignable n'empeche pas l'application de fonctionner. Les deux ne tiennent
 * pas ensemble : avec une expiration a 2 h, la premiere requete arrivee a 2 h 01 pendant
 * une panne de CelesTrak ne trouve plus rien et ne peut que renvoyer une erreur.
 *
 * <p>D'ou l'inversion : <strong>rien n'expire</strong>. Les 2 h ne declenchent plus une
 * eviction mais une <em>tentative</em> de rafraichissement. Si elle reussit, le snapshot
 * est remplace ; si elle echoue, l'ancien est servi avec son age reel, et c'est
 * l'interface qui previent l'utilisateur. Un TLE de trois jours reste exploitable —
 * moins precis, pas faux — et c'est precisement ce que le projet cherche a montrer.
 * Caffeine sert donc de magasin <em>borne</em> ({@code maximumSize}), pas de cache.
 *
 * <h2>Les deux limites</h2>
 * <ul>
 *   <li>{@link TleTooOldException} au-dela de {@code tle.max-age} : la degradation
 *       s'arrete la ou la prediction cesse d'avoir un sens.</li>
 *   <li>{@link TleNotFoundException} n'est jamais degradee, et <em>oublie</em> le
 *       satellite. Un objet qui disparait du catalogue est le plus souvent rentre dans
 *       l'atmosphere ; continuer a propager son dernier TLE afficherait les passages
 *       d'un satellite qui n'existe plus.</li>
 * </ul>
 *
 * <h2>Un seul appel reseau par satellite</h2>
 * Le rafraichissement se fait dans {@code asMap().compute(...)}, atomique par cle chez
 * Caffeine : dix requetes simultanees sur l'ISS donnent un appel a CelesTrak, pas dix —
 * ce que la documentation de CelesTrak demande explicitement. Contrepartie assumee : un
 * appel reseau a lieu sous le verrou de la cle. Il est borne par les timeouts du client
 * (quelques secondes), et seuls les appelants du <em>meme</em> satellite attendent.
 */
@Component
public class TleStore {

    private static final Logger log = LoggerFactory.getLogger(TleStore.class);

    private final CelestrakTleClient client;
    private final TleProperties properties;
    private final Clock clock;
    private final Cache<Integer, TleSnapshot> store;

    public TleStore(CelestrakTleClient client, TleProperties properties, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.store = Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .build();
    }

    /**
     * Le TLE le plus recent dont on dispose pour ce satellite.
     *
     * @throws TleNotFoundException    numero absent du catalogue de CelesTrak.
     * @throws TleUnavailableException CelesTrak injoignable et aucun TLE anterieur.
     * @throws TleTooOldException      le seul TLE disponible est trop vieux pour servir.
     */
    public TleSnapshot get(int noradId) {
        TleSnapshot snapshot = store.asMap().compute(noradId, (id, existing) -> {
            if (existing != null && !needsRefresh(existing)) {
                return existing;
            }
            try {
                return client.fetch(id);
            } catch (TleNotFoundException e) {
                return null; // Caffeine retire l'entree : le satellite est sorti du catalogue.
            } catch (TleUnavailableException e) {
                if (existing == null) {
                    throw e;
                }
                log.warn("CelesTrak indisponible pour {} ({}) — TLE du {} conserve",
                        id, e.getMessage(), existing.fetchedAt());
                return existing;
            }
        });

        if (snapshot == null) {
            throw new TleNotFoundException(noradId);
        }
        return checkAge(snapshot);
    }

    private boolean needsRefresh(TleSnapshot snapshot) {
        Instant now = clock.instant();
        return snapshot.ageSinceFetch(now).compareTo(properties.refreshAfter()) >= 0;
    }

    private TleSnapshot checkAge(TleSnapshot snapshot) {
        Duration age = snapshot.ageSinceEpoch(clock.instant());
        if (age.compareTo(properties.maxAge()) > 0) {
            throw new TleTooOldException(snapshot.noradId(), age, properties.maxAge());
        }
        return snapshot;
    }
}
