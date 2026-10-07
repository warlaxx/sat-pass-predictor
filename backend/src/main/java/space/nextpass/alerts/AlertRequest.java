package space.nextpass.alerts;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A sign-up as the page sends it (ABD-42). The compact constructor is the validation:
 * an {@link IllegalArgumentException} is a 400 with its message, as everywhere else in
 * the API.
 *
 * @param maxMagnitude null for any brightness
 * @param timeZone     an IANA region the browser reports ({@code Europe/Paris}): the
 *                     e-mail gives local times, and "one reminder a day" is a local day
 * @param locale       {@code en} or {@code fr}, the language of the page, then of the e-mails
 */
public record AlertRequest(String email,
                           Integer noradId,
                           Double lat,
                           Double lon,
                           Integer minElevationDeg,
                           Integer maxCloudPercent,
                           Double maxMagnitude,
                           String timeZone,
                           String locale) {

    /** Deliberately loose: one @, a dot in the domain, no spaces. The confirmation e-mail is the real test. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+$");

    public AlertRequest {
        if (email == null || email.strip().length() > 254 || !EMAIL.matcher(email.strip()).matches()) {
            throw new IllegalArgumentException("email is not a valid address");
        }
        email = email.strip().toLowerCase(Locale.ROOT);
        if (noradId == null || noradId < 1 || noradId > 339_999) {
            throw new IllegalArgumentException("noradId must be within [1, 339999]");
        }
        if (lat == null || !(lat >= -90 && lat <= 90) || lon == null || !(lon >= -180 && lon <= 180)) {
            throw new IllegalArgumentException("lat must be within [-90, 90] and lon within [-180, 180]");
        }
        minElevationDeg = minElevationDeg == null ? 30 : minElevationDeg;
        if (minElevationDeg < 10 || minElevationDeg > 80) {
            throw new IllegalArgumentException("minElevationDeg must be within [10, 80]");
        }
        maxCloudPercent = maxCloudPercent == null ? 25 : maxCloudPercent;
        if (maxCloudPercent < 0 || maxCloudPercent > 100) {
            throw new IllegalArgumentException("maxCloudPercent must be within [0, 100]");
        }
        if (maxMagnitude != null && !(maxMagnitude >= -6 && maxMagnitude <= 6)) {
            throw new IllegalArgumentException("maxMagnitude must be within [-6, 6]");
        }
        timeZone = zone(timeZone).getId();
        locale = "fr".equals(locale) ? "fr" : "en";
    }

    private static ZoneId zone(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("timeZone is missing");
        }
        try {
            ZoneId zone = ZoneId.of(id);
            if (zone instanceof ZoneOffset) {
                throw new IllegalArgumentException("timeZone must be a region such as Europe/Paris, not an offset");
            }
            return zone;
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("timeZone is not a known region: " + id);
        }
    }

    /** About a kilometre: enough for the passes, too coarse to point at a house. */
    static double rounded(double degrees) {
        return Math.round(degrees * 100) / 100.0;
    }
}
