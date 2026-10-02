package space.nextpass.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import space.nextpass.usage.UsageRepository;

/** The usage counters live in the database, so they exist only where it does. */
@Configuration
public class UsageConfig {

    @Bean
    @ConditionalOnProperty(name = "api-access.enabled", havingValue = "true")
    public UsageRepository usageRepository(HikariDataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
        return new UsageRepository(jdbc);
    }
}
