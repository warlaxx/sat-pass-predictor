package dev.abdallah.satpass.billing;

import com.stripe.exception.StripeException;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class BillingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StripeGateway stripe;
    private final BillingProperties properties;
    private final String origin;
    public BillingService(JdbcTemplate jdbc, TransactionTemplate transaction, StripeGateway stripe,
                          BillingProperties properties, String origin) {
        this.jdbc = jdbc; this.transaction = transaction; this.stripe = stripe; this.properties = properties; this.origin = origin;
    }
    private Map<String, Object> lock(String owner) {
        return jdbc.queryForMap("SELECT * FROM customer_accounts WHERE github_id = ? FOR UPDATE", owner);
    }
    public String checkout(String owner, String plan) {
        String price = properties.price(plan);
        return transaction.execute(tx -> {
            var account = lock(owner);
            try {
                String customer = (String) account.get("stripe_customer_id");
                if (customer == null) {
                    customer = stripe.customer(owner);
                    jdbc.update("UPDATE customer_accounts SET stripe_customer_id = ? WHERE github_id = ?", customer, owner);
                }
                if (stripe.entitlement(customer).subscribed()) return stripe.portal(customer, origin);
                String previous = (String) account.get("checkout_session_id");
                if (previous != null) {
                    var session = stripe.checkout(previous);
                    if ("open".equals(session.status())) return session.url();
                }
                int generation = ((Number) account.get("checkout_generation")).intValue() + 1;
                var session = stripe.checkout(customer, price, origin, generation);
                jdbc.update("UPDATE customer_accounts SET checkout_session_id = ?, checkout_generation = ? WHERE github_id = ?",
                        session.id(), generation, owner);
                return session.url();
            } catch (StripeException e) { throw new IllegalStateException("Stripe unavailable", e); }
        });
    }
    public String portal(String owner) {
        String customer = (String) jdbc.queryForMap("SELECT stripe_customer_id FROM customer_accounts WHERE github_id = ?", owner).get("stripe_customer_id");
        if (customer == null) throw new IllegalArgumentException("No billing account yet");
        try { return stripe.portal(customer, origin); }
        catch (StripeException e) { throw new IllegalStateException("Stripe unavailable", e); }
    }
    public void reconcile(String eventId, String customer) {
        transaction.executeWithoutResult(tx -> {
            var rows = jdbc.queryForList("SELECT github_id FROM customer_accounts WHERE stripe_customer_id = ? FOR UPDATE", customer);
            if (rows.isEmpty()) return; // Other products may use the same Stripe account.
            if (jdbc.update("INSERT INTO billing_events (event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 0) return;
            try {
                var entitlement = stripe.entitlement(customer);
                String plan = entitlement.plan();
                String owner = (String) rows.getFirst().get("github_id");
                jdbc.update("UPDATE customer_accounts SET plan = ?, checkout_session_id = CASE WHEN ? THEN NULL ELSE checkout_session_id END WHERE github_id = ?", plan, entitlement.subscribed(), owner);
                jdbc.update("""
                    UPDATE api_keys SET plan = ?, monthly_limit = ?, daily_limit = 2147483647, minute_limit = ?
                    WHERE id = (SELECT key_id FROM customer_accounts WHERE github_id = ?)
                    """, plan, monthlyLimit(plan), minuteLimit(plan), owner);
            } catch (StripeException e) { throw new IllegalStateException("Stripe unavailable", e); }
            // Receipt and entitlement commit together. Failures return 503 and are safe to replay.
        });
    }
    public static int monthlyLimit(String plan) { return switch (plan) { case "pro" -> 250000; case "hobby" -> 25000; default -> 1000; }; }
    public static int minuteLimit(String plan) { return switch (plan) { case "pro" -> 120; case "hobby" -> 60; default -> 10; }; }
}
