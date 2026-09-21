package dev.abdallah.satpass.billing;

import java.sql.Timestamp;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class BillingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BillingGateway stripe;
    private final BillingProperties properties;
    public BillingService(JdbcTemplate jdbc, TransactionTemplate transaction, BillingGateway stripe, BillingProperties properties) {
        this.jdbc = jdbc; this.transaction = transaction; this.stripe = stripe; this.properties = properties;
    }
    public String checkout(String owner, String plan) {
        String price = properties.price(plan);
        return transaction.execute(status -> {
            var account = lock(owner);
            if (account.get("key_id") == null) throw new IllegalArgumentException("Create your API key before subscribing.");
            String customer = (String) account.get("stripe_customer_id");
            if (customer == null) {
                customer = stripe.createCustomer(owner);
                jdbc.update("UPDATE customer_accounts SET stripe_customer_id = ? WHERE github_id = ?", customer, owner);
            }
            if (stripe.entitlement(customer).hasSubscription())
                throw new IllegalArgumentException("Use Manage subscription to change your existing subscription.");
            int attempt = ((Number) account.get("checkout_attempt")).intValue();
            String previous = (String) account.get("checkout_id");
            if (previous != null) {
                var session = stripe.retrieveCheckout(previous);
                if ("open".equals(session.status())) {
                    if (!plan.equals(account.get("checkout_plan")))
                        throw new IllegalArgumentException("An existing Checkout is open for " + account.get("checkout_plan") + ". Resume that plan or wait for Checkout to expire.");
                    return session.url();
                }
                if (!"expired".equals(session.status()))
                    throw new IllegalArgumentException("Checkout completed. Refresh your account or manage your subscription.");
                attempt++;
            }
            // The plan is intentionally excluded: retries cannot create a second subscription.
            // Stripe rejects changed parameters after an ambiguous response; retry the original plan.
            var session = stripe.checkout(customer, price, "satpass-checkout-" + owner + "-" + attempt);
            jdbc.update("UPDATE customer_accounts SET checkout_id = ?, checkout_attempt = ?, checkout_plan = ? WHERE github_id = ?",
                    session.id(), attempt, plan, owner);
            return session.url();
        });
    }
    public String portal(String owner) {
        return transaction.execute(status -> {
            String customer = (String) lock(owner).get("stripe_customer_id");
            if (customer == null) throw new IllegalArgumentException("No subscription customer exists yet.");
            return stripe.portal(customer);
        });
    }
    private Map<String, Object> lock(String owner) {
        var rows = jdbc.queryForList("SELECT * FROM customer_accounts WHERE github_id = ? FOR UPDATE", owner);
        if (rows.isEmpty()) throw new IllegalArgumentException("Account is unavailable.");
        return rows.getFirst();
    }
    public void reconcile(String eventId, String customer) {
        transaction.executeWithoutResult(status -> {
            // Serialize before fetching Stripe's current state, including across application replicas.
            var rows = jdbc.queryForList("SELECT * FROM customer_accounts WHERE stripe_customer_id = ? FOR UPDATE", customer);
            if (rows.isEmpty()) return; // Other products in the same Stripe account.
            if (jdbc.update("INSERT INTO billing_events (event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 0) return;
            var entitlement = stripe.entitlement(customer);
            jdbc.update("UPDATE api_keys SET billing_plan = ?, monthly_limit = ?, paid_until = ? WHERE id = ?",
                    entitlement.plan(), entitlement.plan() == null ? null : entitlement.monthlyLimit(),
                    entitlement.until() == null ? null : Timestamp.from(entitlement.until()), rows.getFirst().get("key_id"));
            // Permit a new Checkout after a canceled subscription, while keeping the retry generation unique.
            if (!entitlement.hasSubscription() && rows.getFirst().get("checkout_id") != null) {
                var session = stripe.retrieveCheckout((String) rows.getFirst().get("checkout_id"));
                if (!"open".equals(session.status())) jdbc.update("""
                        UPDATE customer_accounts SET checkout_id = NULL, checkout_plan = NULL, checkout_attempt = checkout_attempt + 1
                        WHERE stripe_customer_id = ?
                        """, customer);
            }
        });
    }
}
