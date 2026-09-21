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
    private final BillingService service;
    private final BillingProperties properties;
    public BillingController(BillingService service, BillingProperties properties) { this.service = service; this.properties = properties; }
    @PostMapping("/account/api/billing/checkout")
    public ResponseEntity<?> checkout(@AuthenticationPrincipal OAuth2User user, @RequestParam String plan) {
        return reply(Map.of("url", service.checkout(user.getName(), plan)));
    }
    @PostMapping("/account/api/billing/portal")
    public ResponseEntity<?> portal(@AuthenticationPrincipal OAuth2User user) {
        return reply(Map.of("url", service.portal(user.getName())));
    }
    @PostMapping("/billing/webhook")
    public ResponseEntity<?> webhook(@RequestBody String body, @RequestHeader(value = "Stripe-Signature", defaultValue = "") String signature) {
        String eventId;
        String customer;
        try {
            Webhook.Signature.verifyHeader(body, signature, properties.webhookSecret(), 300);
            var event = JsonMapper.builder().build().readTree(body);
            String type = event.get("type").asText();
            if (!(type.startsWith("customer.subscription.") || type.equals("invoice.paid")
                    || type.equals("invoice.payment_failed") || type.equals("checkout.session.completed"))) return reply(Map.of("received", true));
            eventId = event.get("id").asText();
            customer = event.path("data").path("object").get("customer").asText();
            if (!eventId.startsWith("evt_") || !customer.startsWith("cus_")) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Invalid Stripe webhook."));
        }
        service.reconcile(eventId, customer);
        return reply(Map.of("received", true));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> badRequest(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("detail", failure.getMessage()));
    }
    @ExceptionHandler({BillingUnavailable.class, org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class})
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).header("Cache-Control", "no-store")
                .body(Map.of("detail", "Billing is unavailable. Please retry later."));
    }
    private ResponseEntity<?> reply(Object body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
}
