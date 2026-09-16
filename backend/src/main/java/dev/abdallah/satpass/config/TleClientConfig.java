package dev.abdallah.satpass.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring of the HTTP client to CelesTrak.
 *
 * <p>The {@link RestClient} is built here rather than inside the client itself: tests
 * inject a {@code RestClient} bound to {@code MockRestServiceServer}, without having to
 * undo a request factory that is already in place.
 *
 * <p>It starts from {@code RestClient.builder()} and not from the auto-configured
 * {@code RestClient.Builder}: in Spring Boot 4 that auto-configuration lives in a
 * separate module which {@code starter-web} does not pull in. Rather than add a
 * dependency for a single client whose every setting we spell out anyway, we build it
 * without one. Worth knowing if a second client appears: it will inherit no shared
 * defaults.
 *
 * <p>Both timeouts are explicit, and they live in two different places because they are
 * two different things: establishing the connection belongs to the JDK
 * {@link HttpClient}, reading the response to Spring's request factory. Left alone, both
 * default to infinite: a CelesTrak that accepts the connection and then never answers
 * would tie up server threads, and the store's graceful degradation would never kick in —
 * it would rest entirely on the remote service's goodwill.
 */
@Configuration
@EnableConfigurationProperties(TleProperties.class)
public class TleClientConfig {

    @Bean
    public RestClient celestrakRestClient(TleProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
