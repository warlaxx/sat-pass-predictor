package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks several sources in order and returns the first usable answer.
 *
 * <h2>Why a list and not a single base URL</h2>
 * CelesTrak drops packets coming from the shared outbound IP ranges of at least two Render
 * regions. The JVM reports a <em>connect</em> timeout — no refusal, no DNS failure — while
 * the same host completes a TCP handshake from another datacenter in 10 ms. With one base
 * URL that is a total outage: the store holds nothing at boot, so the very first request
 * of a fresh instance comes back 503 and the application looks broken to everyone who
 * opens it.
 *
 * <p>One source is one point of failure, and the platform it runs on gets a vote on
 * whether that source is reachable. The list is the smallest answer that does not hand
 * that vote away.
 *
 * <h2>Why "not found" stops the chain</h2>
 * {@link TleNotFoundException} is a statement about the catalogue, not about a connection:
 * the service answered, and said the object is not in it. Every endpoint configured today
 * serves the same CelesTrak catalogue, so trying the next one would be asking one service
 * the same question twice — and it would turn a clean 404 into a slow one.
 *
 * <p>That reasoning holds exactly as long as the list stays one catalogue. The day
 * Space-Track joins it, this is the rule to revisit; it has a test of its own so that
 * revisiting it is a decision and not an accident.
 *
 * <h2>Why every failure is kept</h2>
 * With several endpoints, one message stops being the diagnosis: "unreachable" from the
 * origin and "502" from the relay are two different repairs in two different places. The
 * first failure becomes the cause and the later ones are attached as suppressed
 * exceptions, so a single stack trace in the log names every endpoint that was tried and
 * what each one answered.
 */
public class FallbackTleClient implements TleClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackTleClient.class);

    private final List<TleClient> sources;

    public FallbackTleClient(List<TleClient> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("a TLE chain needs at least one source");
        }
        this.sources = List.copyOf(sources);
    }

    /** How many sources this chain will try. */
    public int size() {
        return sources.size();
    }

    @Override
    public TleSnapshot fetch(int noradId) {
        TleUnavailableException firstFailure = null;

        for (int i = 0; i < sources.size(); i++) {
            try {
                TleSnapshot snapshot = sources.get(i).fetch(noradId);
                if (i > 0) {
                    log.info("TLE source {} of {} answered for satellite {} after {} failure(s)",
                            i + 1, sources.size(), noradId, i);
                }
                return snapshot;
            } catch (TleUnavailableException e) {
                // Logged at each step rather than only at the end: a chain that ends up
                // succeeding still hides an endpoint that is down, and that is exactly the
                // failure nobody notices until the last one goes too.
                log.warn("TLE source {} of {} failed for satellite {}: {}",
                        i + 1, sources.size(), noradId, e.getMessage(), e);
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }

        throw new TleUnavailableException(
                "no TLE source could answer for satellite " + noradId
                        + " (" + sources.size() + " tried)", firstFailure);
    }
}
