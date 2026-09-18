package dev.abdallah.satpass.config;

import dev.abdallah.satpass.tle.CelestrakTleClient;
import dev.abdallah.satpass.tle.FallbackTleClient;
import dev.abdallah.satpass.tle.RequestBudget;
import dev.abdallah.satpass.tle.SpaceTrackTleClient;
import dev.abdallah.satpass.tle.TleClient;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
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
 * <h2>Two HTTP clients, not one</h2>
 * The CelesTrak endpoints share one {@link HttpClient}: it is thread-safe, it carries the
 * connection pool, and the timeouts describe how long <em>this application</em> is
 * willing to wait, not how slow a particular host is. Space-Track gets its own, because
 * it is the only one that needs a cookie jar, and a session cookie has no business being
 * offered to hosts that never asked for one.
 */
@Configuration
@EnableConfigurationProperties({TleProperties.class, SpaceTrackProperties.class})
public class TleClientConfig {

    private static final Logger log = LoggerFactory.getLogger(TleClientConfig.class);

    @Bean
    public TleClient tleClient(TleProperties properties,
                               SpaceTrackProperties spaceTrack,
                               DataContext dataContext,
                               Clock clock) {
        List<TleClient> sources = new ArrayList<>(celestrakSources(properties, dataContext, clock));

        if (spaceTrack.configured()) {
            sources.add(spaceTrackSource(spaceTrack, properties, dataContext, clock));
        } else {
            // Said once, out loud. A second source that is silently absent is worth less
            // than no second source, because you believe you have one.
            log.info("Space-Track is not configured (SPACETRACK_IDENTITY / SPACETRACK_PASSWORD):"
                    + " the chain ends at the CelesTrak endpoints");
        }

        // Printed at startup rather than discovered from a failure. Which sources an
        // instance will actually try is the first thing anyone wants to know when the
        // deployed application and the local one disagree.
        log.info("TLE sources, in order: {}{}", properties.baseUrls(),
                spaceTrack.configured() ? " then " + spaceTrack.baseUrl() + " (space-track)" : "");
        log.info("TLE HTTP budgets: connect={}, request={}, source cooldown={}",
                properties.connectTimeout(), properties.readTimeout(), properties.sourceCooldown());
        return new FallbackTleClient(sources, properties.sourceCooldown(), clock);
    }

    private List<TleClient> celestrakSources(TleProperties properties,
                                             DataContext dataContext,
                                             Clock clock) {
        JdkClientHttpRequestFactory factory = requestFactory(properties, null);
        return properties.baseUrls().stream()
                .map(baseUrl -> (TleClient) new CelestrakTleClient(
                        baseUrl,
                        RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(),
                        dataContext,
                        clock))
                .toList();
    }

    private TleClient spaceTrackSource(SpaceTrackProperties spaceTrack,
                                       TleProperties properties,
                                       DataContext dataContext,
                                       Clock clock) {
        // ACCEPT_ALL rather than the default ACCEPT_ORIGINAL_SERVER: the login and the
        // query are the same host, but a redirect through www. would otherwise drop the
        // cookie and turn every query into a re-login.
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        RestClient client = RestClient.builder()
                .baseUrl(spaceTrack.baseUrl())
                .requestFactory(requestFactory(properties, cookies))
                .build();
        RequestBudget budget = new RequestBudget(
                spaceTrack.requestsPerMinute(), spaceTrack.requestsPerHour(), clock);

        log.info("Space-Track joins the chain as its last source, capped at {}/min and {}/h",
                spaceTrack.requestsPerMinute(), spaceTrack.requestsPerHour());
        return new SpaceTrackTleClient(spaceTrack.baseUrl(), client, dataContext, clock,
                budget, spaceTrack.identity(), spaceTrack.password());
    }

    private JdkClientHttpRequestFactory requestFactory(TleProperties properties,
                                                       CookieManager cookies) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (cookies != null) {
            builder.cookieHandler(cookies);
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
