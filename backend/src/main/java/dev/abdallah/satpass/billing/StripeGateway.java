package dev.abdallah.satpass.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import java.util.List;
import java.util.Map;

/** No global API key; finite timeouts and stable keys make retried writes safe. */
public class StripeGateway {
    private final BillingProperties properties;
    public StripeGateway(BillingProperties properties) { this.properties = properties; }
    private RequestOptions options(String key) {
        return RequestOptions.builder().setApiKey(properties.secretKey()).setConnectTimeout(3000)
                .setReadTimeout(5000).setMaxNetworkRetries(0).setIdempotencyKey(key).build();
    }
    public String customer(String githubId) throws StripeException {
        return Customer.create(Map.of("metadata", Map.of("github_id", githubId)), options("account-" + githubId)).getId();
    }
    public record Checkout(String id, String url, String status) {}
    public Checkout checkout(String customer, String price, String origin, int generation) throws StripeException {
        var session = Session.create(Map.of("customer", customer, "mode", "subscription",
                "line_items", List.of(Map.of("price", price, "quantity", 1)),
                "success_url", origin + "/account/?billing=returned", "cancel_url", origin + "/account/"),
                options("checkout-" + customer + "-" + generation));
        return new Checkout(session.getId(), session.getUrl(), session.getStatus());
    }
    public Checkout checkout(String id) throws StripeException {
        var session = Session.retrieve(id, options(null));
        return new Checkout(session.getId(), session.getUrl(), session.getStatus());
    }
    public String portal(String customer, String origin) throws StripeException {
        return com.stripe.model.billingportal.Session.create(Map.of("customer", customer,
                "return_url", origin + "/account/"), options(null)).getUrl();
    }
    public record Entitlement(String plan, boolean subscribed) {}
    public Entitlement entitlement(String customer) throws StripeException {
        String plan = "free";
        boolean subscribed = false;
        // Read current state: an old delivery or a replay must never restore an old plan.
        for (var subscription : Subscription.list(Map.of("customer", customer, "status", "all", "limit", 100), options(null)).autoPagingIterable()) {
            String status = subscription.getStatus();
            if (List.of("canceled", "incomplete_expired").contains(status)) continue;
            subscribed = true; // Delinquent/incomplete subscriptions must be managed in the Portal too.
            if (!List.of("active", "trialing").contains(status)) continue;
            if (subscription.getItems().getHasMore() || subscription.getItems().getData().size() != 1)
                throw new IllegalStateException("Expected one flat subscription item");
            var item = subscription.getItems().getData().getFirst();
            String price = item.getPrice().getId();
            if (!Long.valueOf(1).equals(item.getQuantity())) throw new IllegalStateException("Expected quantity one");
            String candidate = price.equals(properties.proPrice()) ? "pro" : price.equals(properties.hobbyPrice()) ? "hobby" : null;
            if (candidate == null) throw new IllegalStateException("Unmapped Stripe price");
            if (!plan.equals("pro")) plan = candidate;
        }
        return new Entitlement(plan, subscribed);
    }
}
