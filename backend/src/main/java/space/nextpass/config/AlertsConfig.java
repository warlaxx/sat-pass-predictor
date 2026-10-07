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
import org.springframework.web.client.RestClient;
import space.nextpass.alerts.AlertEmails;
import space.nextpass.alerts.AlertRepository;
import space.nextpass.alerts.AlertService;
import space.nextpass.alerts.ResendMailer;
import space.nextpass.passes.PassQueryService;
import space.nextpass.tle.TleStore;
import space.nextpass.weather.CloudCoverService;

/**
 * Wiring of the e-mail reminders (ABD-42). Only with {@code alerts.enabled}, which needs
 * the database: without {@code api-access.enabled} there is no {@link HikariDataSource}
 * and startup fails, saying so, rather than a page that signs people up into nothing.
 */
@Configuration
@EnableConfigurationProperties(AlertsProperties.class)
public class AlertsConfig {

    @Bean
    @ConditionalOnProperty(name = "alerts.enabled", havingValue = "true")
    public AlertService alertService(AlertsProperties properties, HikariDataSource source, TleStore tles,
                                     PassQueryService passes, CloudCoverService clouds, Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        RestClient resend = RestClient.builder()
                .baseUrl(properties.resendUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.resendApiKey())
                .defaultHeader(HttpHeaders.USER_AGENT, WeatherConfig.USER_AGENT)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return new AlertService(new AlertRepository(jdbc), new ResendMailer(resend, properties.from()),
                new AlertEmails(properties.siteUrl(), properties.apiUrl()), tles, passes, clouds, clock,
                new AlertService.Settings(properties.sendFromHour(), properties.maxPerEmail(), properties.dailyCap(),
                        properties.confirmationCap(), properties.pause()));
    }
}
