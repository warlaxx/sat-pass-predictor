package dev.abdallah.satpass.billing;

import static org.assertj.core.api.Assertions.*;
import com.stripe.Stripe;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

/** Real SDK requests against a loopback Stripe fixture, without provider credentials. */
class StripeGatewayTest {
    HttpServer server;
    String originalBase;
    String response;
    int status = 200;
    List<String> requests = new ArrayList<>();
    StripeGateway gateway = new StripeGateway(new BillingProperties(true, "sk_test_fixture", "whsec_fixture", "price_hobby", "price_pro"), "https://api.example");
    @BeforeEach void start() throws Exception {
        originalBase = Stripe.getApiBase();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Idempotency-Key") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); Stripe.overrideApiBase("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { Stripe.overrideApiBase(originalBase); server.stop(0); }
    String subscription(String state, String invoice, String price) {
        return """
                {"object":"list","has_more":false,"data":[{"id":"sub_1","object":"subscription",
                "status":"%s","latest_invoice":{"id":"in_1","object":"invoice","status":"%s"},
                "items":{"object":"list","has_more":false,"data":[{"id":"si_1","object":"subscription_item",
                "quantity":1,"current_period_end":1792368000,"price":{"id":"%s","object":"price"}}]}}]}
                """.formatted(state, invoice, price);
    }
    @Test void onlyPaidActiveKnownSubscriptionsGrantQuotas() {
        response = subscription("active", "paid", "price_hobby");
        var result = gateway.entitlement("cus_1");
        assertThat(result.plan()).isEqualTo("hobby"); assertThat(result.monthlyLimit()).isEqualTo(25000);
        assertThat(result.until()).isEqualTo(Instant.ofEpochSecond(1792368000));
        assertThat(requests.getFirst()).contains("customer=cus_1", "status=all");
        response = subscription("active", "paid", "price_pro");
        assertThat(gateway.entitlement("cus_1").monthlyLimit()).isEqualTo(250000);
        for (String state : List.of("past_due", "unpaid", "incomplete", "trialing", "paused")) {
            response = subscription(state, "paid", "price_hobby");
            assertThat(gateway.entitlement("cus_1").plan()).isNull();
            assertThat(gateway.entitlement("cus_1").hasSubscription()).isTrue();
        }
        response = subscription("active", "open", "price_pro");
        assertThat(gateway.entitlement("cus_1").plan()).isNull();
        response = subscription("active", "paid", "price_unknown");
        assertThat(gateway.entitlement("cus_1").plan()).isNull();
        response = subscription("canceled", "paid", "price_hobby");
        assertThat(gateway.entitlement("cus_1").hasSubscription()).isFalse();
    }
    @Test void cancellationAtPeriodEndKeepsAccessUntilActualCancellation() {
        response = subscription("active", "paid", "price_hobby").replace("\"status\":\"active\"", "\"cancel_at_period_end\":true,\"status\":\"active\"");
        assertThat(gateway.entitlement("cus_1").plan()).isEqualTo("hobby");
    }
    @Test void checkoutUsesTrustedOriginTaxAndIdempotencyAndPortalUsesCustomer() {
        response = "{\"id\":\"cs_1\",\"object\":\"checkout.session\",\"status\":\"open\",\"url\":\"https://checkout.stripe.com/1\"}";
        assertThat(gateway.checkout("cus_1", "price_hobby", "retry-1").id()).isEqualTo("cs_1");
        String request = java.net.URLDecoder.decode(requests.getFirst(), StandardCharsets.UTF_8);
        assertThat(request).contains("retry-1", "customer=cus_1", "mode=subscription", "line_items[0][price]=price_hobby",
                "success_url=https://api.example/account/?billing=returned", "automatic_tax[enabled]=true");
        response = "{\"id\":\"bps_1\",\"object\":\"billing_portal.session\",\"url\":\"https://billing.stripe.com/1\"}";
        assertThat(gateway.portal("cus_1")).isEqualTo("https://billing.stripe.com/1");
        assertThat(java.net.URLDecoder.decode(requests.getLast(), StandardCharsets.UTF_8)).contains("customer=cus_1", "return_url=https://api.example/account/");
    }
    @Test void providerFailureAndIncompleteListsFailClosed() {
        response = "{\"error\":{\"type\":\"api_error\",\"message\":\"sensitive details\"}}"; status = 500;
        assertThatThrownBy(() -> gateway.entitlement("cus_1")).isInstanceOf(BillingUnavailable.class).hasMessageNotContaining("sensitive");
        status = 200; response = "{\"object\":\"list\",\"has_more\":true,\"data\":[]}";
        assertThatThrownBy(() -> gateway.entitlement("cus_1")).isInstanceOf(BillingUnavailable.class);
    }
}
