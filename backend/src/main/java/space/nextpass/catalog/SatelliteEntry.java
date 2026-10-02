package space.nextpass.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One line of the name index: a NORAD number and the name CelesTrak publishes for it.
 *
 * <p>Deliberately no orbital elements. The index only answers "which number is that
 * satellite?"; the elements used for a prediction still come from
 * {@link space.nextpass.tle.TleStore}, with its own validation and freshness rules.
 * Carrying a TLE here would create a second, looser path to a prediction.
 *
 * <p>{@code launch} is the launch part of the international designator, {@code "2026-045"}
 * for {@code 2026-045A}: what groups the satellites of one Starlink launch into one train.
 * Null when the line carries none. It is not part of the search response, whose contract
 * predates it.
 */
public record SatelliteEntry(int noradId, String name, @JsonIgnore String launch) {

    public SatelliteEntry(int noradId, String name) {
        this(noradId, name, null);
    }
}
