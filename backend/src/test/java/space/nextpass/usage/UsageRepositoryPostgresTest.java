package space.nextpass.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsageRepositoryPostgresTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    HikariDataSource source;
    JdbcTemplate jdbc;
    String schema;
    UsageRepository usage;

    @BeforeAll
    void open() {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "nextpass"));
        config.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        source.close();
        config.setSchema(schema);
        source = new HikariDataSource(config);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = new JdbcTemplate(source);
        usage = new UsageRepository(jdbc);
    }

    @BeforeEach
    void empty() {
        jdbc.execute("TRUNCATE usage_counts");
    }

    @AfterAll
    void close() {
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        source.close();
    }

    long count(LocalDate day, UsageEvent event) {
        return jdbc.queryForObject("SELECT coalesce(sum(count), 0) FROM usage_counts WHERE day = ? AND event = ?",
                Long.class, day, event.key());
    }

    @Test
    void countsPerDayAndPerAction() {
        usage.record(UsageEvent.EVENT_OPEN_PASS, DAY);
        usage.record(UsageEvent.EVENT_OPEN_PASS, DAY);
        usage.record(UsageEvent.EVENT_OPEN_PASS, DAY.plusDays(1));
        usage.record(UsageEvent.LIST_OPEN_EVENT, DAY);

        assertThat(count(DAY, UsageEvent.EVENT_OPEN_PASS)).isEqualTo(2);
        assertThat(count(DAY.plusDays(1), UsageEvent.EVENT_OPEN_PASS)).isEqualTo(1);
        assertThat(count(DAY, UsageEvent.LIST_OPEN_EVENT)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_counts", Long.class)).isEqualTo(3);
    }

    @Test
    void aDayStopsAtItsCeiling() {
        jdbc.update("INSERT INTO usage_counts (day, event, count) VALUES (?, ?, ?)",
                DAY, UsageEvent.LIST_SHOW_TABLE.key(), UsageRepository.MAX_PER_DAY);

        usage.record(UsageEvent.LIST_SHOW_TABLE, DAY);

        assertThat(count(DAY, UsageEvent.LIST_SHOW_TABLE)).isEqualTo(UsageRepository.MAX_PER_DAY);
    }
}
