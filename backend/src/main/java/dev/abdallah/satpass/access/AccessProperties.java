package dev.abdallah.satpass.access;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("api-access")
public record AccessProperties(@DefaultValue("false") boolean enabled,
                               @DefaultValue("") String databaseUrl,
                               @DefaultValue("") String databaseUsername,
                               @DefaultValue("") String databasePassword,
                               @DefaultValue List<String> allowedOrigins) {
    public AccessProperties {
        if (enabled && !databaseUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("API access requires a PostgreSQL JDBC URL");
        }
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || origin.contains("*") || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException("CORS requires exact http(s) origins without a path");
            }
        }
    }

    @Override public String toString() { return "AccessProperties[enabled=" + enabled + "]"; }
}
