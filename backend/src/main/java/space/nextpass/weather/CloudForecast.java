package space.nextpass.weather;

import java.time.Instant;
import java.util.List;

/**
 * The cloud cover MET Norway forecasts over one cell of {@link CloudCoverService#CELL_DEG}
 * (ABD-36), for the passes of the next {@link CloudCoverService#HORIZON}.
 *
 * @param latitudeDeg  the centre of the cell that was asked, not the observer's own place
 * @param longitudeDeg the same
 * @param updatedAt    when MET Norway ran the forecast
 * @param hours        oldest first; hourly for the first two or three days, then every six
 *                     hours, which {@link Hour#stepHours()} says
 */
public record CloudForecast(double latitudeDeg, double longitudeDeg, Instant updatedAt, List<Hour> hours) {

    /**
     * @param time         the start of the step
     * @param cloudPercent the share of the sky covered, all levels together, 0 to 100
     * @param stepHours    how long the value holds: the gap to the next one
     */
    public record Hour(Instant time, int cloudPercent, int stepHours) {}
}
