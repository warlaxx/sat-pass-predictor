package dev.abdallah.satpass.config;

import dev.abdallah.satpass.tle.CelestrakTleClient;
import dev.abdallah.satpass.tle.FallbackTleClient;
import dev.abdallah.satpass.tle.TleClient;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import org.orekit.data.DataContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring of the HTTP clients to the TLE sources.
 *
 * <p>The clients are built here rather than inside {@link CelestrakTleClient}: its test
 * injects a {@code RestClient} bound to {@code MockRestServiceServer}, without having to
 * undo a request factory that is already in place. That is also why
 * {@code CelestrakTleClient} is not a {@code @Component} — there is one instance per
 * configured endpoint, and only this class knows how many.
 *
 * <p>It starts from {@code RestClient.builder()} and not from the auto-configured
 * {@code RestClient.Builder}: in Spring Boot 4 that auto-configuration lives in a
 * separate module which {@code starter-web} does not pull in. Rather than add a
 * dependency for clients whose every setting we spell out anyway, we build them without
 * one. Worth knowing if another client appears: it will inherit no shared defaults.
 *
 * <p>Both timeouts are explicit, and they live in two different places because they are
 * two different things: establishing the connection belongs to the JDK
 * {@link HttpClient}, reading the response to Spring's request factory. Left alone, both
 * default to infinite: a source that accepts the connection and then never answers would
 * tie up server threads, and the store's graceful degradation would never kick in — it
 * would rest entirely on the remote service's goodwill.
 *
 * <p>One {@link HttpClient} is shared by every endpoint, on purpose: it is thread-safe,
 * it carries the connection pool, and the timeouts are the same everywhere by definition
 * — they describe how long <em>this application</em> is willing to wait, not how slow a
 * particular host is.
 */
@Configuration
@EnableConfigurationProperties(TleProperties.class)
public class TleClientConfig {

    private static final Logger log = LoggerFactory.getLogger(TleClientConfig.class);

    @Bean
    public TleClient tleClient(TleProperties properties, DataContext dataContext, Clock clock) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());

        List<TleClient> sources = properties.baseUrls().stream()
                .map(baseUrl -> (TleClient) new CelestrakTleClient(
                        baseUrl,
                        RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(),
                        dataContext,
                        clock))
                .toList();

        // Printed at startup rather than discovered from a failure. Which sources an
        // instance will actually try is the first thing anyone wants to know when the
        // deployed application and the local one disagree.
        log.info("TLE sources, in order: {}", properties.baseUrls());
        return new FallbackTleClient(sources);
    }
}
