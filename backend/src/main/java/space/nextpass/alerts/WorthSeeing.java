package space.nextpass.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import space.nextpass.domain.SatellitePass;
import space.nextpass.domain.TrackPoint;
import space.nextpass.weather.CloudForecast;

/**
 * Which passes are worth an e-mail (ABD-42): the rule, alone, with no clock, database or
 * network, so that its cases can be read in a test.
 *
 * <p>A pass qualifies when, all together:
 * <ul>
 *   <li>some of it is <em>potentially visible</em> - the satellite sunlit, the observer's
 *       Sun at least 6° below the horizon, the same flag the pass tables and the calendar
 *       file use;</li>
 *   <li>it climbs to the subscriber's elevation <em>while visible</em>: a pass peaking at
 *       70° in daylight and entering the Earth's shadow at 15° is a 15° pass to the eye;</li>
 *   <li>at its brightest it reaches the subscriber's magnitude, when the satellite has one
 *       ({@code Brightness}); a satellite without one is not held to it, and the e-mail
 *       says no brightness is known;</li>
 *   <li>the cloud cover forecast at its highest visible point is at most the subscriber's.
 *       No forecast for that hour means no e-mail: the promise is a clear sky, and a
 *       pass we cannot vouch for is not one.</li>
 * </ul>
 */
public final class WorthSeeing {

    /**
     * A pass that qualifies, reduced to what the e-mail says.
     *
     * @param highest       the highest visible sample: its time, elevation and direction
     * @param brightest     the lowest magnitude among the visible samples, null when unknown
     */
    public record Pick(SatellitePass pass, Instant visibleFrom, Instant visibleUntil, TrackPoint highest,
                       Double brightest, int cloudPercent) {}

    private WorthSeeing() {}

    public static List<Pick> select(List<SatellitePass> passes, CloudForecast clouds, Instant notBefore,
                                    int minElevationDeg, Double maxMagnitude, int maxCloudPercent) {
        List<Pick> picks = new ArrayList<>();
        for (SatellitePass pass : passes) {
            List<TrackPoint> visible = pass.track().stream().filter(TrackPoint::visible).toList();
            if (visible.isEmpty() || visible.getLast().instant().isBefore(notBefore)) {
                continue;
            }
            TrackPoint highest = visible.stream().max(Comparator.comparingDouble(TrackPoint::elevationDeg)).orElseThrow();
            if (highest.elevationDeg() < minElevationDeg) {
                continue;
            }
            Double brightest = visible.stream().map(TrackPoint::magnitude).filter(Objects::nonNull)
                    .min(Double::compare).orElse(null);
            if (maxMagnitude != null && brightest != null && brightest > maxMagnitude) {
                continue;
            }
            Integer cloud = cloudAt(clouds, highest.instant());
            if (cloud == null || cloud > maxCloudPercent) {
                continue;
            }
            picks.add(new Pick(pass, visible.getFirst().instant(), visible.getLast().instant(), highest,
                    brightest, cloud));
        }
        return picks;
    }

    /** The forecast step holding at {@code instant}, as the pass tables read it (sky.ts). */
    static Integer cloudAt(CloudForecast forecast, Instant instant) {
        if (forecast == null) {
            return null;
        }
        for (CloudForecast.Hour hour : forecast.hours()) {
            Instant end = hour.time().plusSeconds(hour.stepHours() * 3600L);
            if (!instant.isBefore(hour.time()) && instant.isBefore(end)) {
                return hour.cloudPercent();
            }
        }
        return null;
    }
}
