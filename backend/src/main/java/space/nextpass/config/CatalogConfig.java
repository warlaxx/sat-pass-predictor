package space.nextpass.config;

import space.nextpass.catalog.CatalogSource;
import space.nextpass.catalog.CelestrakCatalogSource;
import space.nextpass.catalog.SatelliteCatalog;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring of the name index: one CelesTrak source per {@code tle.base-urls} entry, in the
 * same order, with the same timeouts. Space-Track is not a source here — its query
 * budget belongs to the elements, which a prediction cannot do without.
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogConfig {

    @Bean
    public SatelliteCatalog satelliteCatalog(TleProperties tle, CatalogProperties catalog, Clock clock) {
        JdkClientHttpRequestFactory factory = TleClientConfig.requestFactory(tle, null);
        List<CatalogSource> sources = tle.baseUrls().stream()
                .map(baseUrl -> (CatalogSource) new CelestrakCatalogSource(
                        baseUrl, RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build()))
                .toList();
        return new SatelliteCatalog(sources, catalog.refreshAfter(), catalog.retryAfter(), clock);
    }
}
