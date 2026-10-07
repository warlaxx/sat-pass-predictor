package space.nextpass.config;

import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import space.nextpass.weather.CloudCoverService;

/**
 * Wiring of the cloud cover (ABD-36). Memory only, no database: a forecast is an hour old
 * at most, and a restart costs MET one request per place asked again.
 */
@Configuration
@EnableConfigurationProperties(WeatherProperties.class)
public class WeatherConfig {

    /** MET Norway asks for the application and a way to reach it; a generic one is banned. */
    static final String USER_AGENT = "NextPass/1.0 (https://www.nextpass.space/legal)";

    @Bean
    public CloudCoverService cloudCoverService(WeatherProperties properties, Clock clock) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        RestClient met = RestClient.builder()
                .baseUrl(properties.metUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
        return new CloudCoverService(met, clock, properties.upstreamPerMinute(), properties.maxCells());
    }
}
