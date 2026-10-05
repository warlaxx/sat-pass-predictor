package space.nextpass.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
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
import space.nextpass.images.ImageImport;
import space.nextpass.images.ImageRepository;
import space.nextpass.images.WikimediaImageSource;

/**
 * Wiring of the photographs' import (ABD-45), run by the nightly request after GCAT. Like
 * the GCAT import it needs the database, so it exists only where the database does.
 */
@Configuration
@EnableConfigurationProperties(ImagesProperties.class)
public class ImagesConfig {

    /**
     * Wikimedia's User-Agent policy: a name and a way to reach us. Without it the limit
     * is ten requests a minute; with it, two hundred.
     */
    static final String USER_AGENT = "NextPass/1.0 (https://www.nextpass.space/legal) spring-restclient";

    @Bean
    @ConditionalOnProperty(name = "api-access.enabled", havingValue = "true")
    public ImageImport imageImport(ImagesProperties properties, HikariDataSource source, Clock clock) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(60);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(120);
        WikimediaImageSource wikimedia = new WikimediaImageSource(
                client(properties.wikidataUrl(), properties.connectTimeout(), properties.readTimeout()),
                client(properties.commonsUrl(), properties.connectTimeout(), properties.readTimeout()),
                properties.pause());
        return new ImageImport(wikimedia, new ImageRepository(jdbc), transaction, clock);
    }

    private static RestClient client(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }
}
