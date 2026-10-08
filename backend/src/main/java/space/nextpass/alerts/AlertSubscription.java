package space.nextpass.alerts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One row of {@code pass_alerts} (ABD-42): who to tell, about which satellite, over which
 * place, and what makes a pass worth the e-mail.
 *
 * @param latitudeDeg     rounded to 0.01° on the way in ({@link AlertRequest#rounded})
 * @param maxMagnitude    null for "any brightness"; ignored for a satellite whose
 *                        magnitude is unknown, which is most of them
 * @param lastCheckedOn   local date of the last look, null before the first
 * @param lastSentOn      local date of the last reminder, null before the first
 */
public record AlertSubscription(long id,
                                String email,
                                int noradId,
                                double latitudeDeg,
                                double longitudeDeg,
                                int minElevationDeg,
                                int maxCloudPercent,
                                Double maxMagnitude,
                                ZoneId timeZone,
                                String locale,
                                String token,
                                Instant confirmedAt,
                                LocalDate lastCheckedOn,
                                LocalDate lastSentOn) {

    public boolean french() {
        return "fr".equals(locale);
    }
}
