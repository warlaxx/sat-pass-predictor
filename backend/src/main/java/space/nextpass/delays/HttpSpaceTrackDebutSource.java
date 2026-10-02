package space.nextpass.delays;

import java.util.List;
import space.nextpass.tle.SpaceTrackSession;

/**
 * Asks Space-Track which objects entered its public catalogue over the last week.
 *
 * <h2>A week, every night</h2>
 * Space-Track's documentation suggests {@code DEBUT/>now-1} once a day after 17:00 UTC;
 * the import runs at 18:23 UTC. A one-day window would lose every object of a night the
 * import missed (a cold start that timed out, a GitHub outage), so the window is seven
 * days and the store keeps the first sighting: a week of failed nights costs nothing.
 * The answer stays small — a few hundred rows — and it is two calls on the shared budget
 * (a login, the query), against a limit of 300 an hour.
 */
public class HttpSpaceTrackDebutSource implements SpaceTrackDebutSource {

    static final String QUERY = "/basicspacedata/query/class/satcat_debut/DEBUT/%3Enow-7/format/json";

    private final SpaceTrackSession session;

    public HttpSpaceTrackDebutSource(SpaceTrackSession session) {
        this.session = session;
    }

    @Override
    public List<SpaceTrackDebut> recent() {
        return SpaceTrackDebutParser.parse(session.get(QUERY, "the catalogue debuts"));
    }
}
