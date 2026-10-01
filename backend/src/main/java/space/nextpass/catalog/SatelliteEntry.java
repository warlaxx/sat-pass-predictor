package space.nextpass.catalog;

/**
 * One line of the name index: a NORAD number and the name CelesTrak publishes for it.
 *
 * <p>Deliberately no orbital elements. The index only answers "which number is that
 * satellite?"; the elements used for a prediction still come from
 * {@link space.nextpass.tle.TleStore}, with its own validation and freshness rules.
 * Carrying a TLE here would create a second, looser path to a prediction.
 */
public record SatelliteEntry(int noradId, String name) {
}
