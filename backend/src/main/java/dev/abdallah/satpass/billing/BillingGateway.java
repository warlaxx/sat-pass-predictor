package dev.abdallah.satpass.billing;

import java.time.Instant;

/** Provider boundary: every customer identifier is obtained from the authenticated account. */
public interface BillingGateway {
    record Checkout(String id, String url, String status) {}
    record Entitlement(String plan, int monthlyLimit, Instant until, boolean hasSubscription) {}
    String createCustomer(String identity);
    Checkout checkout(String customer, String price, String idempotencyKey);
    Checkout retrieveCheckout(String id);
    String portal(String customer);
    Entitlement entitlement(String customer);
}
