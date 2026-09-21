package dev.abdallah.satpass.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("billing")
public record BillingProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String secretKey, @DefaultValue("") String webhookSecret,
        @DefaultValue("") String hobbyPrice, @DefaultValue("") String proPrice) {
    public BillingProperties {
        if (enabled && (!secretKey.startsWith("sk_") || !webhookSecret.startsWith("whsec_")
                || !hobbyPrice.startsWith("price_") || !proPrice.startsWith("price_") || hobbyPrice.equals(proPrice))) {
            throw new IllegalArgumentException("Billing requires Stripe credentials and two distinct recurring prices");
        }
    }
    public String price(String plan) {
        return switch (plan) {
            case "hobby" -> hobbyPrice;
            case "pro" -> proPrice;
            default -> throw new IllegalArgumentException("Unknown billing plan");
        };
    }
    @Override public String toString() { return "BillingProperties[enabled=" + enabled + "]"; }
}
