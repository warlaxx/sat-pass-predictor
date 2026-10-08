package space.nextpass.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * E-mail reminders (ABD-42). Off unless {@code enabled}; on, the Resend key and the
 * sender are required, and the database of {@code api-access} too.
 *
 * @param resendUrl       Resend's API, replaced in tests
 * @param resendApiKey    from Resend's dashboard; never in a file
 * @param from            a sender on a domain verified in Resend, {@code NextPass <alerts@nextpass.space>}
 * @param siteUrl         the public site the e-mails link to
 * @param apiUrl          where the one-click unsubscribe POSTs; the site's origin forwards {@code /api/}
 * @param sendFromHour    local hour from which a subscriber's day is looked at
 * @param maxPerEmail     subscriptions one address may hold
 * @param dailyCap        e-mails a UTC day, all kinds: under Resend's free 100
 * @param confirmationCap of which confirmations at most
 * @param pause           between two reminders, under Resend's request rate
 */
@ConfigurationProperties("alerts")
public record AlertsProperties(boolean enabled, String resendUrl, String resendApiKey, String from, String siteUrl,
                               String apiUrl, Integer sendFromHour, Integer maxPerEmail, Integer dailyCap,
                               Integer confirmationCap, Duration pause, Duration connectTimeout,
                               Duration readTimeout) {

    public AlertsProperties {
        resendUrl = blank(resendUrl) ? "https://api.resend.com" : resendUrl;
        siteUrl = blank(siteUrl) ? "https://www.nextpass.space" : siteUrl;
        apiUrl = blank(apiUrl) ? siteUrl : apiUrl;
        sendFromHour = sendFromHour == null ? 15 : sendFromHour;
        maxPerEmail = maxPerEmail == null ? 5 : maxPerEmail;
        dailyCap = dailyCap == null ? 95 : dailyCap;
        confirmationCap = confirmationCap == null ? 30 : confirmationCap;
        pause = pause == null ? Duration.ofMillis(600) : pause;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        if (enabled && (blank(resendApiKey) || blank(from))) {
            throw new IllegalArgumentException("alerts.enabled needs alerts.resend-api-key and alerts.from");
        }
        // The API key travels in a header: never over plain HTTP.
        if (!resendUrl.regionMatches(true, 0, "https://", 0, 8)) {
            throw new IllegalArgumentException("alerts.resend-url must use HTTPS");
        }
        if (sendFromHour < 0 || sendFromHour > 23) {
            throw new IllegalArgumentException("alerts.send-from-hour must be within [0, 23]");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
