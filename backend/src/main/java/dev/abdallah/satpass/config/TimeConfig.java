package dev.abdallah.satpass.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's source of "now".
 *
 * <p>Injected rather than scattered as {@code Instant.now()} calls: the age of a TLE and
 * the decision to refresh it are business rules, and a rule that depends on wall-clock
 * time can only be tested by waiting. Tests substitute a fixed or hand-advanced clock.
 *
 * <p>It lives in its own configuration, not next to the CelesTrak client: the clock is
 * cross-cutting, and the REST layer will need it too.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
