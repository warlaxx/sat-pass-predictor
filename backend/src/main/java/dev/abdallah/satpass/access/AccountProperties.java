package dev.abdallah.satpass.access;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("account")
public record AccountProperties(@DefaultValue("false") boolean enabled,
                                @DefaultValue("") String githubClientId,
                                @DefaultValue("") String githubClientSecret,
                                @DefaultValue("http://localhost:8080") String baseUrl) {
    public AccountProperties {
        if (enabled) {
            URI uri = URI.create(baseUrl);
            boolean local = "http".equals(uri.getScheme()) && "localhost".equals(uri.getHost());
            if ((!local && !"https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException("Account base URL must be an HTTPS origin (HTTP localhost for development)");
            }
            if (githubClientId.isBlank() || githubClientSecret.isBlank()) {
                throw new IllegalArgumentException("GitHub OAuth client ID and secret are required");
            }
        }
    }
    @Override public String toString() { return "AccountProperties[enabled=" + enabled + "]"; }
}
