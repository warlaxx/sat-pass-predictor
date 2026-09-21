package dev.abdallah.satpass.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class StripeGateway implements BillingGateway {
    private final BillingProperties properties;
    private final String returnUrl;
    public StripeGateway(BillingProperties properties, String baseUrl) {
        this.properties = properties;
        this.returnUrl = baseUrl + "/account/";
    }
    private RequestOptions options(String key) {
        return RequestOptions.builder().setApiKey(properties.secretKey()).setIdempotencyKey(key)
                .setConnectTimeout(3000).setReadTimeout(5000).setMaxNetworkRetries(0).build();
    }
    private interface Call<T> { T run() throws StripeException; }
    private <T> T call(Call<T> work) {
        try { return work.run(); }
        catch (StripeException e) { throw new BillingUnavailable(); }
    }
    public String createCustomer(String identity) {
        return call(() -> Customer.create(Map.of("metadata", Map.of("github_id", identity)),
                options("satpass-customer-" + identity)).getId());
    }
    public Checkout checkout(String customer, String price, String key) {
        return call(() -> from(Session.create(Map.of("customer", customer, "mode", "subscription",
                "line_items", List.of(Map.of("price", price, "quantity", 1)),
                "success_url", returnUrl + "?billing=returned", "cancel_url", returnUrl,
                "automatic_tax", Map.of("enabled", true), "customer_update", Map.of("address", "auto")), options(key))));
    }
    public Checkout retrieveCheckout(String id) { return call(() -> from(Session.retrieve(id, options(null)))); }
    private Checkout from(Session session) { return new Checkout(session.getId(), session.getUrl(), session.getStatus()); }
    public String portal(String customer) {
        return call(() -> com.stripe.model.billingportal.Session.create(
                Map.of("customer", customer, "return_url", returnUrl), options(null)).getUrl());
    }
    public Entitlement entitlement(String customer) {
        return call(() -> {
            var subscriptions = Subscription.list(Map.of("customer", customer, "status", "all", "limit", 100,
                    "expand", List.of("data.latest_invoice")), options(null));
            if (Boolean.TRUE.equals(subscriptions.getHasMore())) throw new BillingUnavailable();
            Entitlement result = new Entitlement(null, 0, null, false);
            int ongoing = 0;
            for (var subscription : subscriptions.getData()) {
                if (List.of("canceled", "incomplete_expired").contains(subscription.getStatus())) continue;
                ongoing++;
                result = new Entitlement(null, 0, null, true);
                // A failed/pending invoice, trial or unrecognized price never grants paid access.
                if (!"active".equals(subscription.getStatus()) || subscription.getLatestInvoiceObject() == null
                        || !"paid".equals(subscription.getLatestInvoiceObject().getStatus())) continue;
                var items = subscription.getItems();
                if (Boolean.TRUE.equals(items.getHasMore()) || items.getData().size() != 1) continue;
                var item = items.getData().getFirst();
                if (!Long.valueOf(1).equals(item.getQuantity()) || item.getCurrentPeriodEnd() == null) continue;
                String price = item.getPrice().getId();
                String plan = properties.hobbyPrice().equals(price) ? "hobby" : properties.proPrice().equals(price) ? "pro" : null;
                if (plan != null) result = new Entitlement(plan, plan.equals("hobby") ? 25_000 : 250_000,
                        Instant.ofEpochSecond(item.getCurrentPeriodEnd()), true);
            }
            // Multiple subscriptions require operator resolution; never silently bill twice and choose one.
            if (ongoing > 1) throw new BillingUnavailable();
            return result;
        });
    }
}
