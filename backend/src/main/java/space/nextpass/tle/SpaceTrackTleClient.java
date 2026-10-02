package space.nextpass.tle;

import space.nextpass.domain.TleSnapshot;
import java.time.Clock;
import java.time.Instant;
import org.orekit.data.DataContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches a TLE from Space-Track, the catalogue CelesTrak republishes.
 *
 * <h2>What it is for, and what it is not for</h2>
 * It is an <strong>availability</strong> fallback, not a <strong>coverage</strong> one. It
 * sits last in the chain, behind CelesTrak and behind the relay, and it answers the
 * question "we could not reach the others" — not "the others do not have this object".
 * That distinction is what lets {@link FallbackTleClient} keep stopping on a
 * {@code not found}: CelesTrak is the mirror of the same public catalogue, so its "I do
 * not have it" is as authoritative as Space-Track's would be.
 *
 * <p>It is also what protects the account. {@code /api/passes} is public and its NORAD
 * number is a query parameter; without that rule, a stream of made-up numbers would turn
 * into one authenticated call each — and negative answers are not cached, since the store
 * removes the entry. The chain stops before it gets here.
 *
 * <h2>The session</h2>
 * Login, cookie and request budget belong to {@link SpaceTrackSession}, which this client
 * shares with the nightly catalogue-debut fetch: Space-Track counts calls per account.
 *
 * <h2>Six-digit catalogue numbers</h2>
 * Space-Track writes them in Alpha-5 in its 3LE output, as its documentation states
 * ({@code A0534} for 100534), where CelesTrak serves them only as OMM. Orekit decodes
 * Alpha-5, so the same query and the same {@link TleResponseParser} serve both ranges.
 *
 * <h2>Two ways this differs from CelesTrak, both handled here</h2>
 * <ul>
 *   <li>An object the catalogue does not hold comes back as an <strong>empty
 *       result</strong>, not as a marker in a 200 body. Empty therefore means
 *       {@link TleNotFoundException} here, where it means "failed exchange" for
 *       CelesTrak — the same bytes, opposite meanings, which is exactly why each client
 *       owns that decision and only the parsing is shared.</li>
 *   <li>{@code format/3le} prefixes the name line with {@code 0 }.
 *       {@link TleResponseParser} strips it so that one satellite has one name whichever
 *       source answered.</li>
 * </ul>
 */
public class SpaceTrackTleClient implements TleClient {

    private static final Logger log = LoggerFactory.getLogger(SpaceTrackTleClient.class);

    private static final String SOURCE = "space-track";

    /**
     * The {@code gp} class holds the current element set, one row per object
     * ({@code gp_history} is the historical one). {@code limit/1} costs nothing and says
     * out loud that exactly one TLE is expected.
     */
    private static final String QUERY =
            "/basicspacedata/query/class/gp/NORAD_CAT_ID/%d/limit/1/format/3le";

    private final SpaceTrackSession session;
    private final DataContext dataContext;
    private final Clock clock;

    public SpaceTrackTleClient(SpaceTrackSession session, DataContext dataContext, Clock clock) {
        this.session = session;
        this.dataContext = dataContext;
        this.clock = clock;
    }

    /**
     * @throws TleNotFoundException    if the catalogue does not contain this number.
     * @throws TleUnavailableException if Space-Track is unreachable, refuses the
     *                                 credentials, is over budget, or answers anything
     *                                 other than a usable TLE.
     */
    @Override
    public TleSnapshot fetch(int noradId) {
        String body = session.get(String.format(QUERY, noradId), "satellite " + noradId);
        Instant fetchedAt = clock.instant();

        String trimmed = body == null ? "" : body.strip();
        // Space-Track says "no such object" by returning nothing. Unlike CelesTrak, where
        // an empty body is a truncated exchange, here it is the answer.
        if (trimmed.isEmpty() || "[]".equals(trimmed)) {
            throw new TleNotFoundException(noradId);
        }

        TleSnapshot snapshot = TleResponseParser.parse(
                noradId, session.endpoint(), SOURCE, trimmed, fetchedAt, dataContext);
        log.info("fetched TLE for {} ({}) from {}, epoch {}",
                noradId, snapshot.name(), session.endpoint(), snapshot.epoch());
        return snapshot;
    }
}
