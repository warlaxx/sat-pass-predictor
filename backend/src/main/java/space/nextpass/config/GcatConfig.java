package space.nextpass.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import space.nextpass.gcat.GcatImporter;
import space.nextpass.gcat.GcatRepository;
import space.nextpass.gcat.HttpGcatSource;
import space.nextpass.separations.SeparationRepository;

/**
 * Wiring of the GCAT import. It needs the database, so it exists only where the database
 * does ({@code api-access.enabled}); elsewhere the import endpoint says so.
 */
@Configuration
@EnableConfigurationProperties(GcatProperties.class)
public class GcatConfig {

    @Bean
    @ConditionalOnProperty(name = "api-access.enabled", havingValue = "true")
    public GcatImporter gcatImporter(GcatProperties properties, HikariDataSource source, Clock clock) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                // Says who is downloading, so that GCAT's author can tell us apart and
                // reach us rather than block an anonymous client.
                .defaultHeader(HttpHeaders.USER_AGENT, "NextPass (+https://www.nextpass.space)")
                .build();

        JdbcTemplate jdbc = new JdbcTemplate(source);
        // Per statement: a batch of upserts, or a count over the table.
        jdbc.setQueryTimeout(60);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        // Per file: the download and every batch of it.
        transaction.setTimeout(600);
        return new GcatImporter(new HttpGcatSource(restClient), properties.files(),
                new GcatRepository(jdbc), transaction, clock);
    }

    /** The separation pages read what the import wrote, with a request-sized budget. */
    @Bean
    @ConditionalOnProperty(name = "api-access.enabled", havingValue = "true")
    public SeparationRepository separationRepository(HikariDataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return new SeparationRepository(jdbc);
    }
}
