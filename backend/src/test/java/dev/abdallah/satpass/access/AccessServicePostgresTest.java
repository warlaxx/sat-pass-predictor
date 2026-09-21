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
        jdbc.update("DELETE FROM billing_events");
        jdbc.update("DELETE FROM customer_accounts");
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
    AccountService accounts() {
        return new AccountService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), service, clock);
    }

    @Test void selfServeRotationKeepsQuotaAndInvalidatesOldSecret() {
        var accounts = accounts();
        accounts.register("123");
        accounts.register("123");
        assertThat(accounts.dashboard("123").keyId()).isNull();
        var first = accounts.regenerate("123");
        service.admit(first.secret(), false);
        var next = accounts.regenerate("123");
        assertThat(next.id()).isEqualTo(first.id());
        assertThat(next.secret()).isNotEqualTo(first.secret());
        assertThatThrownBy(() -> service.admit(first.secret(), false)).isInstanceOf(AccessFailure.class);
        assertThat(accounts.dashboard("123").usedToday()).isEqualTo(1);
        assertThat(accounts.dashboard("123").minuteLimit()).isEqualTo(10);
        jdbc.update("UPDATE api_keys SET daily_limit = 1 WHERE id = ?", first.id());
        assertThatThrownBy(() -> service.admit(next.secret(), false))
                .isInstanceOfSatisfying(AccessFailure.class, e -> assertThat(e.status()).isEqualTo(429));
        accounts.revoke("123");
        assertThat(accounts.dashboard("123").active()).isFalse();
        assertThatThrownBy(() -> service.admit(next.secret(), false))
                .isInstanceOfSatisfying(AccessFailure.class, e -> assertThat(e.status()).isEqualTo(401));
        accounts.regenerate("123");
        assertThat(accounts.dashboard("123").dailyLimit()).isEqualTo(1);
        assertThat(accounts.dashboard("123").usedToday()).isEqualTo(1);
        now.set(Instant.parse("2026-09-20T00:00:00Z"));
        assertThat(accounts.dashboard("123").usedToday()).isZero();
    }

    @Test void selfServeOwnersCannotChangeAnotherAccountsKey() {
        var accounts = accounts();
        accounts.register("123"); accounts.register("456");
        var first = accounts.regenerate("123");
        var second = accounts.regenerate("456");
        accounts.revoke("123");
        accounts.regenerate("123");
        service.admit(second.secret(), false);
        assertThat(accounts.dashboard("456").keyId()).isEqualTo(second.id());
        assertThat(accounts.dashboard("456").usedToday()).isEqualTo(1);
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThatThrownBy(() -> accounts.regenerate("789")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> accounts.register("a-login-name")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentSelfServeCreationProducesOnlyOneKeyAndNoQuotaReset() throws Exception {
        accounts().register("123");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<AccessService.IssuedKey>>();
            for (int i = 0; i < 8; i++) jobs.add(workers.submit(() -> { start.await(); return accounts().regenerate("123"); }));
            start.countDown();
            var ids = new java.util.HashSet<UUID>();
            int valid = 0;
            var issued = new java.util.ArrayList<AccessService.IssuedKey>();
            for (var job : jobs) issued.add(job.get(15, java.util.concurrent.TimeUnit.SECONDS));
            for (var key : issued) {
                ids.add(key.id());
                try { service.admit(key.secret(), false); valid++; }
                catch (AccessFailure e) { assertThat(e.status()).isEqualTo(401); }
            }
            assertThat(ids).hasSize(1);
            assertThat(valid).isEqualTo(1);
            assertThat(accounts().dashboard("123").usedToday()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_keys WHERE owner = 'github:123'", Long.class)).isEqualTo(1);
        }
    }

    dev.abdallah.satpass.billing.BillingGateway gateway;
    dev.abdallah.satpass.billing.BillingService billing() {
        return new dev.abdallah.satpass.billing.BillingService(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)), gateway,
                new dev.abdallah.satpass.billing.BillingProperties(true, "sk_test_x", "whsec_x", "price_hobby", "price_pro"));
    }
    AccessService.IssuedKey billingAccount() {
        accounts().register("123");
        var key = accounts().regenerate("123");
        jdbc.update("UPDATE customer_accounts SET stripe_customer_id = 'cus_test' WHERE github_id = '123'");
        gateway = org.mockito.Mockito.mock(dev.abdallah.satpass.billing.BillingGateway.class);
        return key;
    }
    void entitlement(String plan, int quota, String until) {
        org.mockito.Mockito.when(gateway.entitlement("cus_test")).thenReturn(
                new dev.abdallah.satpass.billing.BillingGateway.Entitlement(plan, quota,
                        until == null ? null : Instant.parse(until), plan != null));
    }
    @Test void billingReplayReconcilesCurrentStatePreservesRevocationAndRollsBackFailures() {
        var key = billingAccount();
        service.admit(key.secret(), false);
        entitlement("hobby", 25000, "2026-10-19T12:00:00Z");
        billing().reconcile("evt_new", "cus_test");
        billing().reconcile("evt_new", "cus_test");
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).entitlement("cus_test");
        assertThat(accounts().dashboard("123").plan()).isEqualTo("hobby");
        assertThat(accounts().dashboard("123").usedMonth()).isEqualTo(1);
        accounts().revoke("123");
        entitlement("pro", 250000, "2026-10-19T12:00:00Z");
        billing().reconcile("evt_older_delivered_later", "cus_test");
        assertThat(accounts().dashboard("123").plan()).isEqualTo("pro");
        assertThat(accounts().dashboard("123").active()).isFalse();
        org.mockito.Mockito.when(gateway.entitlement("cus_test")).thenThrow(new dev.abdallah.satpass.billing.BillingUnavailable());
        assertThatThrownBy(() -> billing().reconcile("evt_retry", "cus_test")).isInstanceOf(dev.abdallah.satpass.billing.BillingUnavailable.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_events WHERE event_id = 'evt_retry'", Integer.class)).isZero();
        org.mockito.Mockito.reset(gateway);
        entitlement(null, 0, null);
        billing().reconcile("evt_retry", "cus_test");
        assertThat(accounts().dashboard("123").plan()).isEqualTo("standard");
        assertThat(accounts().dashboard("123").usedToday()).isEqualTo(1);
        assertThat(accounts().dashboard("123").active()).isFalse();
    }
    @Test void monthlyQuotaSurvivesRotationAndResetsOnlyAtUtcMonthBoundary() {
        var key = billingAccount();
        entitlement("hobby", 2, "2026-11-01T00:00:00Z");
        billing().reconcile("evt_1", "cus_test");
        service.admit(key.secret(), false);
        now.set(Instant.parse("2026-09-20T00:00:00Z"));
        service.admit(key.secret(), false);
        var rotated = accounts().regenerate("123");
        assertThatThrownBy(() -> service.admit(rotated.secret(), false)).isInstanceOfSatisfying(AccessFailure.class, e -> {
            assertThat(e.code()).isEqualTo("monthly-quota-exceeded");
            assertThat(e.resetsAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        });
        now.set(Instant.parse("2026-10-01T00:00:00Z"));
        service.admit(rotated.secret(), false);
        assertThat(accounts().dashboard("123").usedMonth()).isEqualTo(1);
        now.set(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(accounts().dashboard("123").plan()).isEqualTo("standard");
        assertThat(accounts().dashboard("123").minuteLimit()).isEqualTo(10);
        jdbc.update("UPDATE api_keys SET daily_limit = 1 WHERE id = ?", key.id());
        service.admit(rotated.secret(), false);
        assertThatThrownBy(() -> service.admit(rotated.secret(), false)).isInstanceOfSatisfying(AccessFailure.class,
                e -> assertThat(e.code()).isEqualTo("daily-quota-exceeded"));
    }
    @Test void checkoutReusesPendingSessionAndExistingSubscriptionsUsePortal() {
        billingAccount();
        entitlement(null, 0, null);
        var checkout = new dev.abdallah.satpass.billing.BillingGateway.Checkout("cs_1", "https://checkout.stripe.com/1", "open");
        org.mockito.Mockito.when(gateway.checkout("cus_test", "price_hobby", "satpass-checkout-123-0")).thenReturn(checkout);
        org.mockito.Mockito.when(gateway.retrieveCheckout("cs_1")).thenReturn(checkout);
        assertThat(billing().checkout("123", "hobby")).isEqualTo(checkout.url());
        assertThat(billing().checkout("123", "hobby")).isEqualTo(checkout.url());
        assertThatThrownBy(() -> billing().checkout("123", "pro")).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).checkout(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());
        entitlement("hobby", 25000, "2026-10-19T12:00:00Z");
        assertThatThrownBy(() -> billing().checkout("123", "pro")).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.when(gateway.portal("cus_test")).thenReturn("https://billing.stripe.com/1");
        assertThat(billing().portal("123")).isEqualTo("https://billing.stripe.com/1");
        accounts().register("456");
        assertThatThrownBy(() -> billing().portal("456")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing().checkout("456", "hobby")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing().checkout("123", "price_attacker")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentBillingEventsApplyOnceAndMonthlyAdmissionCannotOverspend() throws Exception {
        var key = billingAccount();
        entitlement("hobby", 7, "2026-10-19T12:00:00Z");
        try (var workers = Executors.newFixedThreadPool(8)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) jobs.add(workers.submit(() -> billing().reconcile("evt_same", "cus_test")));
            for (var job : jobs) job.get(15, java.util.concurrent.TimeUnit.SECONDS);
            org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).entitlement("cus_test");
            var admissions = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 20; i++) admissions.add(workers.submit(() -> {
                try { instance().admit(key.secret(), false); return true; }
                catch (AccessFailure failure) { assertThat(failure.code()).isEqualTo("monthly-quota-exceeded"); return false; }
            }));
            int accepted = 0;
            for (var job : admissions) if (job.get(15, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(7);
        }
    }
    @Test void expiredCheckoutAndCancellationAdvanceIdempotencyGeneration() {
        billingAccount(); entitlement(null, 0, null);
        jdbc.update("UPDATE customer_accounts SET checkout_id = 'cs_old' WHERE github_id = '123'");
        org.mockito.Mockito.when(gateway.retrieveCheckout("cs_old")).thenReturn(
                new dev.abdallah.satpass.billing.BillingGateway.Checkout("cs_old", null, "expired"));
        org.mockito.Mockito.when(gateway.checkout("cus_test", "price_pro", "satpass-checkout-123-1")).thenReturn(
                new dev.abdallah.satpass.billing.BillingGateway.Checkout("cs_new", "https://checkout.stripe.com/new", "open"));
        assertThat(billing().checkout("123", "pro")).endsWith("/new");
        org.mockito.Mockito.when(gateway.retrieveCheckout("cs_new")).thenReturn(
                new dev.abdallah.satpass.billing.BillingGateway.Checkout("cs_new", null, "complete"));
        billing().reconcile("evt_canceled", "cus_test");
        assertThat(jdbc.queryForObject("SELECT checkout_attempt FROM customer_accounts WHERE github_id = '123'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT checkout_id FROM customer_accounts WHERE github_id = '123'").get("checkout_id")).isNull();
    }

}
