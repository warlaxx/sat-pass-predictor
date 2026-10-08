package space.nextpass.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The separation pages as seen from outside (ABD-15).
 *
 * @param siteUrl the public site the RSS feed links to, and serves the feed from: the
 *                site's origin forwards {@code /api/} to this backend. Its own property,
 *                not the e-mail reminders' one, so that one can move without the other.
 */
@ConfigurationProperties("separations")
public record SeparationsProperties(String siteUrl) {

    public SeparationsProperties {
        siteUrl = siteUrl == null || siteUrl.isBlank() ? "https://www.nextpass.space" : siteUrl.replaceAll("/+$", "");
    }
}
