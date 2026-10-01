package space.nextpass.api;

import space.nextpass.api.PassesResponse.ObserverDto;
import java.util.List;
import org.springframework.http.ProblemDetail;

/**
 * The answer to a batch: one entry per satellite and site, in request order — every site
 * of the first satellite, then every site of the second.
 *
 * <p>An entry holds either a {@code prediction}, shaped exactly like a
 * {@code /v1/passes} response, or an {@code error}, shaped exactly like the Problem
 * Details that endpoint would have returned for the same satellite. Never both, never
 * neither. A satellite missing from the catalogue does not fail the batch: the other
 * nine were computed, and throwing them away to report the tenth would bill the caller
 * for nothing.
 *
 * <p>{@code predictions} is the number of entries, which is also what the batch counted
 * against the key's quotas — failed entries included, since they were admitted before
 * anyone knew they would fail, like a single call that ends in a 404.
 */
public record BatchPassesResponse(int hours,
                                  double minElevationDeg,
                                  boolean track,
                                  int predictions,
                                  List<Entry> results) {

    /**
     * {@code siteIndex} points into the request's {@code site} parameters once
     * duplicates are dropped; {@code observer} repeats the site so an entry can be read
     * on its own, including a failed one.
     */
    public record Entry(int noradId,
                        int siteIndex,
                        ObserverDto observer,
                        PassesResponse prediction,
                        ProblemDetail error) {
    }
}
