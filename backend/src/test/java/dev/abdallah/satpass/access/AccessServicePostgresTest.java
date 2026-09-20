package dev.abdallah.satpass.access;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL: ON CONFLICT and row locks must not be 'verified' against H2. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessServicePostgresTest {
    HikariDataSource source;
    JdbcTemplate jdbc;
    AccessService service;
    String schema;
    AtomicReference<Instant> now = new AtomicReference<>();
    Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @BeforeAll void open() {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        config.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "satpass"));
        config.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        source.close();
        config.setSchema(schema);
        source = new HikariDataSource(config);
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        service = instance();
    }
    AccessService instance() {
        return new AccessService(new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)), clock);
    }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM api_usage");
        jdbc.update("DELETE FROM api_keys WHERE plan != 'demo'");
        jdbc.update("UPDATE api_keys SET minute_start = NULL, minute_used = 0, active = true, daily_limit = 200, minute_limit = 20");
        now.set(Instant.parse("2026-09-19T12:00:30Z"));
    }
    @AfterAll void close() {
        if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        if (source != null) source.close();
    }

    @Test void storesOnlyHashAndRevocationImmediatelyTakesEffect() {
        var key = service.issue("customer", 100, 10);
        var stored = jdbc.queryForMap("SELECT * FROM api_keys WHERE id = ?", key.id());
        assertThat(stored.get("key_hash")).isEqualTo(AccessService.hash(key.secret()));
        assertThat(stored.toString()).doesNotContain(key.secret());
        assertThat(key.toString()).doesNotContain(key.secret());
        service.admit(key.secret(), false);
        assertThat(service.revoke(key.id())).isTrue();
        assertThatThrownBy(() -> instance().admit(key.secret(), true))
                .isInstanceOfSatisfying(AccessFailure.class, e -> assertThat(e.status()).isEqualTo(401));
        assertThat(service.usage(key.id()).getFirst().get("requests")).isEqualTo(1L);
    }

    @Test void unknownOrMalformedKeysNeverFallBackToPublicQuota() {
        for (String key : new String[]{"", "invalid", "spp_" + "a".repeat(43)}) {
            assertThatThrownBy(() -> service.admit(key, true)).isInstanceOf(AccessFailure.class);
        }
        assertThatThrownBy(() -> service.admit(null, false)).isInstanceOf(AccessFailure.class);
        assertThat(service.usage(AccessService.PUBLIC_KEY)).isEmpty();
    }

    @Test void minuteAndDailyLimitsResetAtUtcBoundariesAndRejectionsDoNotCount() {
        var key = service.issue("customer", 2, 1);
        service.admit(key.secret(), false);
        assertThatThrownBy(() -> service.admit(key.secret(), false)).isInstanceOfSatisfying(AccessFailure.class, e -> {
            assertThat(e.code()).isEqualTo("rate-limit-exceeded");
            assertThat(e.resetsAt()).isEqualTo(Instant.parse("2026-09-19T12:01:00Z"));
        });
        now.set(Instant.parse("2026-09-19T12:01:00Z"));
        instance().admit(key.secret(), true); // Same counters through either route and a new service instance.
        assertThatThrownBy(() -> service.admit(key.secret(), false)).isInstanceOfSatisfying(AccessFailure.class, e -> {
            assertThat(e.code()).isEqualTo("daily-quota-exceeded");
            assertThat(e.resetsAt()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
        });
        assertThat(service.usage(key.id()).getFirst().get("requests")).isEqualTo(2L);
        now.set(Instant.parse("2026-09-20T00:00:00Z"));
        instance().admit(key.secret(), false);
        assertThat(service.usage(key.id())).hasSize(2);
    }

    @Test void concurrentInstancesCannotOverspendTheQuota() throws Exception {
        var key = service.issue("concurrent", 7, 100);
        var other = instance();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(12)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 24; i++) {
                var target = i % 2 == 0 ? service : other;
                jobs.add(workers.submit(() -> {
                    start.await();
                    try { target.admit(key.secret(), false); return true; }
                    catch (AccessFailure e) { assertThat(e.status()).isEqualTo(429); return false; }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var job : jobs) if (job.get(15, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(7);
        }
        assertThat(service.usage(key.id()).getFirst().get("requests")).isEqualTo(7L);
    }

    @Test void publicDemoHasItsOwnPersistentBudget() {
        jdbc.update("UPDATE api_keys SET daily_limit = 1 WHERE id = ?", AccessService.PUBLIC_KEY);
        service.admit(null, true);
        assertThatThrownBy(() -> instance().admit(null, true)).isInstanceOf(AccessFailure.class);
        var key = service.issue("independent", 10, 10);
        service.admit(key.secret(), false);
        assertThat(service.usage(key.id()).getFirst().get("requests")).isEqualTo(1L);
    }
}
