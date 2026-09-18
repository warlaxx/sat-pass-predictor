package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
 * <h2>Why a failing source moves to the back, and is never dropped</h2>
 * Measured in production: the first endpoint burned its full connect timeout on
 * <strong>every single</strong> retrieval, having never once answered from that host, and
 * that delay came straight out of the budget the next source had to answer within. Two
 * seconds spent asking a question whose answer is already known, at the moment it is most
 * expensive.
 *
 * <p>So a source that fails goes to the back of the queue for a while, and a source that
 * answers returns to its place. Demoted, never removed — the order changes, the list does
 * not. A breaker that <em>excludes</em> a source has to decide when to let it back in, and
 * gets to be wrong in the one direction that matters: manufacturing an outage out of a
 * service that had recovered. This one cannot. In the worst case — every source cooling —
 * the order is exactly the configured one, which is where we started.
 */
public class FallbackTleClient implements TleClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackTleClient.class);

    private final List<TleClient> sources;
    private final Duration cooldown;
    private final Clock clock;

    /** Per source: when it stops being demoted, or {@code null} while it is trusted. */
    private final AtomicReferenceArray<Instant> coolingUntil;

    public FallbackTleClient(List<TleClient> sources, Duration cooldown, Clock clock) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("a TLE chain needs at least one source");
        }
        if (cooldown == null || cooldown.isNegative()) {
            throw new IllegalArgumentException("the source cooldown must not be negative");
        }
        this.sources = List.copyOf(sources);
        this.cooldown = cooldown;
        this.clock = clock;
        this.coolingUntil = new AtomicReferenceArray<>(this.sources.size());
    }

    /** How many sources this chain will try. */
    public int size() {
        return sources.size();
    }

    @Override
    public TleSnapshot fetch(int noradId) {
        List<Integer> order = order(clock.instant());
        TleUnavailableException firstFailure = null;
        int attempt = 0;

        for (int index : order) {
            attempt++;
            try {
                TleSnapshot snapshot = sources.get(index).fetch(noradId);
                // Back to being trusted: the next retrieval asks it first again.
                coolingUntil.set(index, null);
                if (attempt > 1) {
                    log.info("TLE source {} answered for satellite {} after {} failure(s)",
                            index + 1, noradId, attempt - 1);
                }
                return snapshot;
            } catch (TleUnavailableException e) {
                coolingUntil.set(index, clock.instant().plus(cooldown));
                // Logged at each step rather than only at the end: a chain that ends up
                // succeeding still hides an endpoint that is down, and that is exactly the
                // failure nobody notices until the last one goes too.
                log.warn("TLE source {} of {} failed for satellite {}, demoted for {}: {}",
                        index + 1, sources.size(), noradId, cooldown, e.getMessage(), e);
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

    /**
     * Configured order, with the sources still cooling moved to the back. A stable
     * partition: among the trusted ones, and among the demoted ones, the configured order
     * is kept — the fallback stays a fallback.
     */
    private List<Integer> order(Instant now) {
        List<Integer> trusted = new ArrayList<>(sources.size());
        List<Integer> demoted = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            Instant until = coolingUntil.get(i);
            if (until == null || !now.isBefore(until)) {
                trusted.add(i);
            } else {
                demoted.add(i);
            }
        }
        trusted.addAll(demoted);
        return trusted;
    }
}
