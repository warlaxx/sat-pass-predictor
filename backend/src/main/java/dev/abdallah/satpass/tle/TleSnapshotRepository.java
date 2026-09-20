package dev.abdallah.satpass.tle;

import dev.abdallah.satpass.domain.TleSnapshot;
import java.util.Optional;

/**
 * Elements that outlive the process.
 *
 * <h2>Why a second place to keep a TLE</h2>
 * {@link TleStore} holds the last known elements in memory, which is enough for a demo:
 * a restart loses them and the next request fetches again. That is a different statement
 * once someone is paying for the call that arrives during the restart — it is CelesTrak's
 * availability, not this service's, that decides whether it is answered. Persisting the
 * snapshot moves the upstream out of the request path in the common case.
 *
 * <h2>This is a store, not a cache — again</h2>
 * Nothing expires here either, for the reason given at length in {@link TleStore}: an
 * old TLE is less accurate, not wrong, and the age limit that does matter
 * ({@code tle.max-age}) is applied when it is served, not when it is written. The row is
 * deleted only when the catalogue says the object is gone.
 *
 * <h2>Every method must tolerate the database being down</h2>
 * The whole point is availability, so an implementation that throws when PostgreSQL is
 * unreachable would trade one dependency for another. Implementations swallow and log;
 * {@link #find} then returns empty and {@link #save} is a no-op, which lands the caller
 * back on the in-memory behaviour it had before.
 */
public interface TleSnapshotRepository {

    /** The persisted elements for this satellite, if any are stored and readable. */
    Optional<TleSnapshot> find(int noradId);

    /** Stores these elements, unless a strictly more recent fetch is already stored. */
    void save(TleSnapshot snapshot);

    /** Forgets this satellite: the catalogue no longer has it. */
    void remove(int noradId);

    /**
     * What runs when no database is configured — the default deployment.
     *
     * <p>Not {@code null} and not an {@code Optional} at every call site: the store's
     * logic is identical with and without persistence, and a null check repeated three
     * times would be the only difference.
     */
    TleSnapshotRepository NONE = new TleSnapshotRepository() {
        @Override public Optional<TleSnapshot> find(int noradId) { return Optional.empty(); }
        @Override public void save(TleSnapshot snapshot) { }
        @Override public void remove(int noradId) { }
        @Override public String toString() { return "no persistent TLE store"; }
    };
}
