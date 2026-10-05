package space.nextpass.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the objects' photographs come from (ABD-45).
 *
 * @param wikidataUrl    Wikidata's SPARQL endpoint
 * @param commonsUrl     Commons' {@code api.php}
 * @param pause          between two Commons requests
 * @param connectTimeout budget to open a connection
 * @param readTimeout    budget for each read of a body
 */
@ConfigurationProperties("images")
public record ImagesProperties(String wikidataUrl, String commonsUrl, Duration pause,
                               Duration connectTimeout, Duration readTimeout) {

    public ImagesProperties {
        if (wikidataUrl == null || wikidataUrl.isBlank() || commonsUrl == null || commonsUrl.isBlank()) {
            throw new IllegalArgumentException("images.wikidata-url and images.commons-url must be set");
        }
        pause = pause == null ? Duration.ofSeconds(1) : pause;
    }
}
