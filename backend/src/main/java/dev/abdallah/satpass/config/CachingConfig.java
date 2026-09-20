package dev.abdallah.satpass.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.abdallah.satpass.passes.PredictionCacheProperties;
import dev.abdallah.satpass.tle.PostgresTleSnapshotRepository;
import dev.abdallah.satpass.tle.TleSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What milestone 12 adds so that a call is not paid for twice: the settings of the
 * in-memory prediction cache, and the persistent elements behind the TLE store.
 *
 * <p>Persistence follows the database, not a switch of its own. There is exactly one
 * reason this application talks to PostgreSQL — {@code api-access.enabled} — and adding a
 * second flag would create a state ("keys on, TLEs off") that nobody needs and that
 * someone would have to reason about during an incident. Hence
 * {@link ObjectProvider}: the data source is there or it is not, and the store falls back
 * to {@link TleSnapshotRepository#NONE}, which is the behaviour the default demo has
 * always had.
 *
 * <p>A separate {@link JdbcTemplate} rather than the one the access service uses: this
 * one carries a two-second query timeout, and lending it to quota accounting — which runs
 * inside a transaction with its own budget — would silently change that budget.
 */
@Configuration
@EnableConfigurationProperties(PredictionCacheProperties.class)
public class CachingConfig {

    private static final Logger log = LoggerFactory.getLogger(CachingConfig.class);

    @Bean
    public TleSnapshotRepository tleSnapshotRepository(ObjectProvider<HikariDataSource> dataSource) {
        HikariDataSource source = dataSource.getIfAvailable();
        if (source == null) {
            log.info("No database: TLEs live in memory only, and a restart fetches them again");
            return TleSnapshotRepository.NONE;
        }
        log.info("TLEs are persisted: a restart serves the stored elements instead of calling upstream");
        return new PostgresTleSnapshotRepository(new JdbcTemplate(source));
    }
}
