package dev.abdallah.satpass.access;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(AccessProperties.class)
public class AccessConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "api-access.enabled", havingValue = "true")
    public HikariDataSource accessDataSource(AccessProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.databaseUrl());
        config.setUsername(properties.databaseUsername());
        config.setPassword(properties.databasePassword());
        config.setMaximumPoolSize(5);
        // Let a free Neon compute suspend when the API is idle.
        config.setMinimumIdle(0);
        config.setIdleTimeout(60_000);
        config.setKeepaliveTime(0);
        config.setConnectionTimeout(3000);
        config.addDataSourceProperty("connectTimeout", "3");
        config.addDataSourceProperty("socketTimeout", "10");
        HikariDataSource source = new HikariDataSource(config);
        try {
            Flyway.configure().dataSource(source).load().migrate();
            return source;
        } catch (RuntimeException failure) {
            source.close();
            throw failure;
        }
    }

    @Bean
    @ConditionalOnProperty(name = "api-access.enabled", havingValue = "true")
    public AccessService accessService(HikariDataSource source, Clock clock) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(5);
        return new AccessService(new JdbcTemplate(source), transaction, clock);
    }
}
