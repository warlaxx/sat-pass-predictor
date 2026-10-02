package space.nextpass.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds NORAD numbers by satellite name, from an index held in memory.
 *
 * <h2>Why an index, and not a CelesTrak query per search</h2>
 * A search box sends a request every few keystrokes. Forwarding each one to CelesTrak
 * would multiply calls to a service that already blocks this application's hosting
 * provider, and would make typing a name depend on its availability. So the whole
 * {@code active} group is downloaded once, and every search after that is a scan of a
 * few thousand strings in memory.
 *
 * <h2>Refresh rules</h2>
 * The same shape as {@link space.nextpass.tle.TleStore}, for the same reasons:
 * <ul>
 *   <li>nothing is downloaded at startup — an instance that restarts and is never
 *       searched costs CelesTrak nothing;</li>
 *   <li>past {@code refreshAfter} the next search tries again, and a failure keeps the
 *       previous index: names do not go stale the way elements do;</li>
 *   <li>no new attempt before {@code retryAfter}, so an outage is not turned into one
 *       download per keystroke.</li>
 * </ul>
 * Only the very first download makes a request wait. After that, the one request that
 * finds the index due refreshes it while concurrent ones are served the previous index.
 *
 * <h2>Ranking</h2>
 * Names are compared upper-case with punctuation folded to spaces, so {@code iss zarya}
 * finds {@code ISS (ZARYA)}. Exact name first, then names starting with the query, then
 * names with a word starting with it, then any substring; shorter names first within a
 * rank, so {@code ISS (ZARYA)} comes before {@code ISS OBJECT XK}. A query made of digits
 * also matches the NORAD number exactly, ranked above every name.
 *
 * <h2>Launches</h2>
 * {@link #latestLaunches} answers "which Starlink launch is the newest?" from the same
 * index: the satellites whose name starts with the query, grouped by the launch of their
 * international designator, newest launch first. A satellite enters CelesTrak's active
 * group only once it is catalogued, a day or more after launch, so the very first hours of
 * a train are not there.
 */
public class SatelliteCatalog {

    private static final Logger log = LoggerFactory.getLogger(SatelliteCatalog.class);

    /** Below this, a name query matches a large part of the catalogue and says nothing. */
    public static final int MIN_QUERY_LENGTH = 2;

    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]+");
    /** Up to six digits: numbers above 99999 are given since July 2026. */
    private static final Pattern NORAD_NUMBER = Pattern.compile("\\d{1,6}");

    private final List<CatalogSource> sources;
    private final Duration refreshAfter;
    private final Duration retryAfter;
    private final Clock clock;
    private final ReentrantLock refreshing = new ReentrantLock();

    private volatile Index index;
    private volatile Instant lastAttempt;

    public SatelliteCatalog(List<CatalogSource> sources,
                            Duration refreshAfter,
                            Duration retryAfter,
                            Clock clock) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("a catalogue needs at least one source");
        }
        this.sources = List.copyOf(sources);
        this.refreshAfter = refreshAfter;
        this.retryAfter = retryAfter;
        this.clock = clock;
    }

    /** The matches for one query, and the age of the index that produced them. */
    public record Result(List<SatelliteEntry> matches, Instant catalogFetchedAt) {
    }

    /**
     * The satellites of one launch: its designator ({@code "2026-045"}), how many of them
     * the index holds, and the lowest-numbered one, whose passes stand for the group's
     * while it still flies as a train.
     */
    public record Launch(String designator, int satellites, int noradId, String name) {
    }

    /** The newest launches, and the age of the index that produced them. */
    public record Launches(List<Launch> launches, Instant catalogFetchedAt) {
    }

    /**
     * @throws IllegalArgumentException    if the query is too short to mean anything.
     * @throws CatalogUnavailableException if no index has ever been downloaded.
     */
    public Result search(String query, int limit) {
        String needle = normalize(query == null ? "" : query);
        boolean numeric = NORAD_NUMBER.matcher(needle).matches();
        if (!numeric) {
            requireName(needle, "of the satellite name, or its NORAD number");
        }
        Index current = current();

        record Match(Indexed entry, int rank) {
        }
        List<Match> matches = new ArrayList<>();
        int wanted = numeric ? Integer.parseInt(needle) : -1;
        for (Indexed entry : current.entries()) {
            int rank = rank(entry, needle, wanted);
            if (rank >= 0) {
                matches.add(new Match(entry, rank));
            }
        }
        List<SatelliteEntry> ranked = matches.stream()
                .sorted(Comparator.comparingInt(Match::rank)
                        .thenComparingInt(match -> match.entry().normalized().length())
                        .thenComparingInt(match -> match.entry().entry().noradId()))
                .limit(limit)
                .map(match -> match.entry().entry())
                .toList();
        return new Result(ranked, current.fetchedAt());
    }

    /**
     * Launches of the satellites whose name starts with {@code query}, newest first.
     *
     * @throws IllegalArgumentException    if the query is too short to mean anything.
     * @throws CatalogUnavailableException if no index has ever been downloaded.
     */
    public Launches latestLaunches(String query, int limit) {
        String needle = normalize(query == null ? "" : query);
        requireName(needle, "of the start of the satellite names");
        Index current = current();

        Map<String, List<SatelliteEntry>> byLaunch = new LinkedHashMap<>();
        for (Indexed entry : current.entries()) {
            String launch = entry.entry().launch();
            if (launch != null && entry.normalized().startsWith(needle)) {
                byLaunch.computeIfAbsent(launch, key -> new ArrayList<>()).add(entry.entry());
            }
        }
        List<Launch> launches = byLaunch.entrySet().stream()
                // "2026-045" sorts as text: four-digit year, then a zero-padded number.
                .sorted(Map.Entry.<String, List<SatelliteEntry>>comparingByKey().reversed())
                .limit(limit)
                .map(group -> {
                    SatelliteEntry first = group.getValue().stream()
                            .min(Comparator.comparingInt(SatelliteEntry::noradId))
                            .orElseThrow();
                    return new Launch(group.getKey(), group.getValue().size(), first.noradId(), first.name());
                })
                .toList();
        return new Launches(launches, current.fetchedAt());
    }

    private static void requireName(String needle, String what) {
        if (needle.length() < MIN_QUERY_LENGTH) {
            throw new IllegalArgumentException("Type at least " + MIN_QUERY_LENGTH
                    + " letters or digits " + what + ".");
        }
    }

    /** Lower is better; negative means no match. */
    private static int rank(Indexed entry, String needle, int wanted) {
        if (entry.entry().noradId() == wanted) return 0;
        String name = entry.normalized();
        if (name.equals(needle)) return 1;
        if (name.startsWith(needle)) return 2;
        if (entry.spaced().contains(" " + needle)) return 3;
        if (name.contains(needle)) return 4;
        return -1;
    }

    static String normalize(String text) {
        return NOT_ALPHANUMERIC.matcher(text.toUpperCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private Index current() {
        Index held = index;
        if (due(held)) {
            // Cold: every request has to wait for the one download. Warm: one request
            // refreshes, the others are served the index already held.
            if (held == null) {
                refreshing.lock();
            } else if (!refreshing.tryLock()) {
                return held;
            }
            try {
                if (due(index)) {
                    refresh();
                }
            } finally {
                refreshing.unlock();
            }
            held = index;
        }
        if (held == null) {
            throw new CatalogUnavailableException(
                    "the satellite catalogue has not been downloaded yet and no source answered");
        }
        return held;
    }

    private boolean due(Index held) {
        Instant now = clock.instant();
        Instant attempted = lastAttempt;
        if (attempted != null && now.isBefore(attempted.plus(retryAfter))) {
            return false;
        }
        return held == null || !now.isBefore(held.fetchedAt().plus(refreshAfter));
    }

    private void refresh() {
        Instant now = clock.instant();
        lastAttempt = now;
        for (CatalogSource source : sources) {
            try {
                List<SatelliteEntry> entries = source.fetchAll();
                index = new Index(entries.stream().map(Indexed::of).toList(), now);
                log.info("satellite catalogue: {} names from {}", entries.size(), source.endpoint());
                return;
            } catch (CatalogUnavailableException e) {
                log.warn("satellite catalogue source {} failed: {}", source.endpoint(), e.getMessage(), e);
            }
        }
        log.warn("no satellite catalogue source answered; {}", index == null
                ? "name search is unavailable until one does"
                : "keeping the index fetched at " + index.fetchedAt());
    }

    private record Indexed(SatelliteEntry entry, String normalized, String spaced) {
        static Indexed of(SatelliteEntry entry) {
            String normalized = normalize(entry.name());
            return new Indexed(entry, normalized, " " + normalized);
        }
    }

    private record Index(List<Indexed> entries, Instant fetchedAt) {
    }
}
