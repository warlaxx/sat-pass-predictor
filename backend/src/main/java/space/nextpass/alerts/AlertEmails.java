package space.nextpass.alerts;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import space.nextpass.domain.TrackPoint;

/**
 * The two e-mails of ABD-42, in English and in French: the confirmation, and the reminder
 * of the day. Plain text first - it is what an e-mail client shows in a preview and what
 * a screen reader reads - and a minimal HTML twin with no image, tracker or remote style.
 *
 * <p>Links point at the site, never at the API: a confirmation or an unsubscription is a
 * page that asks the API with a POST. Mail scanners open every link of a message they
 * inspect, and a GET that confirmed or unsubscribed would do it on the reader's behalf.
 * The one-click header is a POST by definition (RFC 8058), so it may point at the API.
 */
public final class AlertEmails {

    /** Never more passes than this in one e-mail: three good passes in a night is already rare. */
    static final int MAX_PASSES = 3;

    private static final String[] COMPASS = "N NNE NE ENE E ESE SE SSE S SSW SW WSW W WNW NW NNW".split(" ");
    private static final String[] COMPASS_FR = "N NNE NE ENE E ESE SE SSE S SSO SO OSO O ONO NO NNO".split(" ");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final String siteUrl;
    private final String apiUrl;

    /**
     * @param siteUrl the public site, {@code https://www.nextpass.space}
     * @param apiUrl  where the one-click unsubscribe POST goes; the site's own origin works,
     *                since it forwards {@code /api/}
     */
    public AlertEmails(String siteUrl, String apiUrl) {
        this.siteUrl = trim(siteUrl);
        this.apiUrl = trim(apiUrl);
    }

    public Email confirmation(AlertSubscription alert, String satelliteName) {
        boolean fr = alert.french();
        String link = page(alert, "/alerts/confirm") + "?token=" + encode(alert.token());
        String subject = fr ? "Confirmez vos rappels de passage NextPass" : "Confirm your NextPass pass reminders";
        String what = fr
                ? "Vous (ou quelqu'un qui a saisi votre adresse) avez demandé un e-mail les jours où "
                        + satelliteName + " fait un beau passage au-dessus de " + place(alert) + "."
                : "You (or someone who typed your address) asked for an e-mail on the days "
                        + satelliteName + " makes a good pass over " + place(alert) + ".";
        String act = fr ? "Pour l'activer, ouvrez ce lien dans les 48 heures et appuyez sur « Confirmer mes rappels » :"
                : "To turn it on, open this link within 48 hours and press \"Confirm my reminders\":";
        String ignore = fr
                ? "Sans confirmation, l'inscription et votre adresse sont effacées au bout de 48 heures. Rien d'autre ne vous sera envoyé."
                : "Without confirmation, the sign-up and your address are deleted after 48 hours. Nothing else will be sent.";
        String text = String.join("\n\n", what, act + "\n" + link, criteria(alert), ignore) + "\n\n-- \nNextPass · " + siteUrl;
        String html = html(List.of(
                p(escape(what)),
                "<p><a href=\"" + escape(link) + "\">" + escape(fr ? "Confirmer mes rappels" : "Confirm my reminders") + "</a></p>",
                p(escape(criteria(alert))),
                small(escape(ignore))));
        return new Email(alert.email(), subject, text, html, null,
                "confirm-" + alert.id() + "-" + alert.token().substring(0, 8));
    }

    public Email reminder(AlertSubscription alert, String satelliteName, List<WorthSeeing.Pick> picks,
                          LocalDate day) {
        boolean fr = alert.french();
        List<WorthSeeing.Pick> shown = picks.subList(0, Math.min(MAX_PASSES, picks.size()));
        WorthSeeing.Pick first = shown.getFirst();
        String start = TIME.format(local(alert, first.visibleFrom()));
        String subject = fr
                ? satelliteName + " : visible à " + start + ", ciel " + sky(first.cloudPercent(), true)
                : satelliteName + ": visible at " + start + ", " + sky(first.cloudPercent(), false) + " sky";
        String intro = fr
                ? (shown.size() == 1 ? "Un beau passage" : shown.size() + " beaux passages") + " de " + satelliteName
                        + " au-dessus de " + place(alert) + " d'ici demain midi (heure locale, " + alert.timeZone().getId() + ") :"
                : (shown.size() == 1 ? "A good pass" : shown.size() + " good passes") + " of " + satelliteName
                        + " over " + place(alert) + " before noon tomorrow (local time, " + alert.timeZone().getId() + "):";
        List<String> lines = shown.stream().map(pick -> line(alert, pick)).toList();
        String brightness = first.brightest() == null
                ? (fr ? "La luminosité de ce satellite n'est pas connue : visible veut dire éclairé par le Soleil dans un ciel noir."
                        : "This satellite's brightness is not known: visible means sunlit against a dark sky.")
                : null;
        String link = page(alert, "/") + "?norad=" + alert.noradId() + "&lat=" + alert.latitudeDeg()
                + "&lon=" + alert.longitudeDeg();
        String more = fr ? "Carte du ciel, trajectoire et heures exactes" : "Sky chart, ground track and exact times";
        String unsubscribe = page(alert, "/alerts/unsubscribe") + "?token=" + encode(alert.token());
        String footer = fr
                ? "Vous recevez cet e-mail parce que vous avez confirmé un rappel NextPass, au plus un par jour. "
                        + "Prévision nuageuse : MET Norway (CC BY 4.0). Heures calculées avec les éléments orbitaux du jour."
                : "You get this e-mail because you confirmed a NextPass reminder, at most one a day. "
                        + "Cloud forecast: MET Norway (CC BY 4.0). Times computed from today's orbital elements.";
        String stop = fr ? "Ne plus recevoir ces rappels" : "Stop these reminders";
        String colon = fr ? " :" : ":";

        List<String> text = new ArrayList<>();
        text.add(intro);
        text.add(String.join("\n", lines.stream().map(l -> "- " + l).toList()));
        if (brightness != null) {
            text.add(brightness);
        }
        text.add(more + colon + "\n" + link);
        text.add(footer);
        text.add(stop + colon + "\n" + unsubscribe);
        List<String> html = new ArrayList<>();
        html.add(p(escape(intro)));
        html.add("<ul>" + String.join("", lines.stream().map(l -> "<li>" + escape(l) + "</li>").toList()) + "</ul>");
        if (brightness != null) {
            html.add(p(escape(brightness)));
        }
        html.add("<p><a href=\"" + escape(link) + "\">" + escape(more) + "</a></p>");
        html.add(small(escape(footer)));
        html.add(small("<a href=\"" + escape(unsubscribe) + "\">" + escape(stop) + "</a>"));
        String unsubscribeApi = apiUrl + "/api/alerts/unsubscribe?token=" + encode(alert.token());
        return new Email(alert.email(), subject, String.join("\n\n", text) + "\n\n-- \nNextPass · " + siteUrl,
                html(html), unsubscribeApi, "reminder-" + alert.id() + "-" + day);
    }

    private String line(AlertSubscription alert, WorthSeeing.Pick pick) {
        boolean fr = alert.french();
        TrackPoint highest = pick.highest();
        String from = TIME.format(local(alert, pick.visibleFrom()));
        String until = TIME.format(local(alert, pick.visibleUntil()));
        String direction = compass(highest.azimuthDeg(), fr);
        String elevation = Math.round(highest.elevationDeg()) + "°";
        String magnitude = pick.brightest() == null ? "" : ", magnitude " + magnitude(pick.brightest(), fr);
        String clouds = fr ? ", nuages " + pick.cloudPercent() + " %" : ", clouds " + pick.cloudPercent() + "%";
        String day = DateTimeFormatter.ofPattern("EEE d", fr ? Locale.FRENCH : Locale.ENGLISH)
                .format(local(alert, pick.visibleFrom()));
        return fr
                ? day + ", " + from + " → " + until + " : jusqu'à " + elevation + " vers le " + direction + magnitude + clouds
                : day + ", " + from + " → " + until + ": up to " + elevation + " towards " + direction + magnitude + clouds;
    }

    private String criteria(AlertSubscription alert) {
        boolean fr = alert.french();
        String magnitude = alert.maxMagnitude() == null ? ""
                : ", magnitude " + magnitude(alert.maxMagnitude(), fr)
                        + (fr ? " ou plus brillant (si elle est connue)" : " or brighter (when known)");
        return fr
                ? "Un beau passage : satellite éclairé dans un ciel noir, à au moins " + alert.minElevationDeg()
                        + "° au-dessus de l'horizon" + magnitude + ", et au plus " + alert.maxCloudPercent() + " % de nuages prévus."
                : "A good pass: sunlit satellite in a dark sky, at least " + alert.minElevationDeg()
                        + "° above the horizon" + magnitude + ", and at most " + alert.maxCloudPercent() + "% cloud forecast.";
    }

    private static String place(AlertSubscription alert) {
        return String.format(Locale.ROOT, "%.2f, %.2f", alert.latitudeDeg(), alert.longitudeDeg());
    }

    /** "−1.8", with a true minus sign; a decimal comma in French. */
    static String magnitude(double value, boolean fr) {
        return String.format(fr ? Locale.FRANCE : Locale.ROOT, "%.1f", value).replace('-', '−');
    }

    private static String sky(int cloudPercent, boolean fr) {
        if (cloudPercent <= 25) {
            return fr ? "dégagé" : "clear";
        }
        return fr ? "partiellement nuageux" : "partly cloudy";
    }

    static String compass(double azimuthDeg, boolean fr) {
        double normalised = ((azimuthDeg % 360) + 360) % 360;
        return (fr ? COMPASS_FR : COMPASS)[(int) Math.round(normalised / 22.5) % 16];
    }

    private static ZonedDateTime local(AlertSubscription alert, Instant instant) {
        return instant.atZone(alert.timeZone());
    }

    private String page(AlertSubscription alert, String path) {
        return siteUrl + (alert.french() ? "/fr" : "") + path;
    }

    private static String html(List<String> blocks) {
        return "<!doctype html><html><body style=\"font-family:system-ui,sans-serif;line-height:1.5;max-width:560px\">"
                + String.join("", blocks) + "</body></html>";
    }

    private static String p(String content) {
        return "<p>" + content + "</p>";
    }

    private static String small(String content) {
        return "<p style=\"color:#666;font-size:13px\">" + content + "</p>";
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trim(String url) {
        return url.replaceAll("/+$", "");
    }
}
