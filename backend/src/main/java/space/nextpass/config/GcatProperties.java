package space.nextpass.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the GCAT import downloads from.
 *
 * @param baseUrl        the directory holding the catalogue files, without trailing slash
 * @param files          the files to import, in order; see {@code GcatImporter} for why
 *                       there is more than one
 * @param connectTimeout budget to open the connection
 * @param readTimeout    budget for each read of the body, not for the whole download
 */
@ConfigurationProperties("gcat")
public record GcatProperties(String baseUrl, List<String> files, Duration connectTimeout, Duration readTimeout) {

    public GcatProperties {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("gcat.base-url must be set, without a trailing slash");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("gcat.files must name at least one file");
        }
    }
}
