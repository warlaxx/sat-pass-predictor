package dev.abdallah.satpass.billing;

import tools.jackson.databind.json.JsonMapper;
import com.stripe.net.Webhook;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "billing.enabled", havingValue = "true")
public class BillingController {
    private final BillingService billing;
    private final BillingProperties properties;
    public BillingController(BillingService billing, BillingProperties properties) { this.billing = billing; this.properties = properties; }
    @PostMapping("/account/api/billing/checkout")
    public ResponseEntity<?> checkout(@AuthenticationPrincipal OAuth2User user, @RequestParam String plan) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("url", billing.checkout(user.getName(), plan)));
    }
    @PostMapping("/account/api/billing/portal")
    public ResponseEntity<?> portal(@AuthenticationPrincipal OAuth2User user) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("url", billing.portal(user.getName())));
    }
    @PostMapping("/billing/webhook")
    public ResponseEntity<?> webhook(@RequestBody String body, @RequestHeader("Stripe-Signature") String signature) {
        try { Webhook.constructEvent(body, signature, properties.webhookSecret()); }
        catch (Exception invalid) { return ResponseEntity.badRequest().build(); }
        var event = JsonMapper.builder().build().readTree(body);
        String type = event.get("type").asString();
        if (type.equals("customer.subscription.created") || type.equals("customer.subscription.updated")
                || type.equals("customer.subscription.deleted") || type.equals("checkout.session.completed")
                || type.equals("checkout.session.async_payment_succeeded") || type.equals("checkout.session.async_payment_failed")) {
            var customer = event.path("data").path("object").get("customer");
            if (customer == null || customer.isNull()) return ResponseEntity.badRequest().build();
            billing.reconcile(event.get("id").asString(), customer.asString());
        }
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("detail", "Invalid billing request.")); }
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).header("Cache-Control", "no-store")
            .body(Map.of("detail", "Billing is temporarily unavailable. Please retry.")); }
}
