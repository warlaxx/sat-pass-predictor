package dev.abdallah.satpass.billing;

import static org.assertj.core.api.Assertions.*;
import com.stripe.Stripe;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

class StripeGatewayTest {
    HttpServer server;
    StripeGateway gateway;
    String subscriptions = "";
    List<String> requests = new ArrayList<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            requests.add(exchange.getRequestMethod() + " " + path + " " + exchange.getRequestHeaders().getFirst("Idempotency-Key")
                    + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = path.startsWith("/v1/subscriptions") ? "{\"object\":\"list\",\"url\":\"/v1/subscriptions\",\"has_more\":false,\"data\":[" + subscriptions + "]}"
                    : path.startsWith("/v1/customers") ? "{\"id\":\"cus_test\",\"object\":\"customer\"}"
                    : path.startsWith("/v1/billing_portal") ? "{\"id\":\"bps_test\",\"object\":\"billing_portal.session\",\"url\":\"https://billing.stripe.com/test\"}"
                    : "{\"id\":\"cs_test\",\"object\":\"checkout.session\",\"status\":\"open\",\"url\":\"https://checkout.stripe.com/test\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); Stripe.overrideApiBase("http://127.0.0.1:" + server.getAddress().getPort());
        gateway = new StripeGateway(new BillingProperties(true, "sk_test", "whsec_test", "price_hobby", "price_pro"));
    }
    @AfterEach void stop() { Stripe.overrideApiBase(Stripe.LIVE_API_BASE); server.stop(0); }
    String subscription(String status, String price) {
        return "{\"id\":\"sub_test\",\"object\":\"subscription\",\"status\":\"" + status
                + "\",\"items\":{\"object\":\"list\",\"has_more\":false,\"data\":[{\"id\":\"si_test\",\"object\":\"subscription_item\",\"quantity\":1,\"price\":{\"id\":\"" + price + "\",\"object\":\"price\"}}]}}";
    }
    @Test void activeAndTrialingGetFixedPlanWhileDelinquencyAndCancellationRemovePaidAccess() throws Exception {
        for (String status : List.of("active", "trialing")) {
            subscriptions = subscription(status, "price_pro");
            assertThat(gateway.entitlement("cus_test")).isEqualTo(new StripeGateway.Entitlement("pro", true));
        }
        for (String status : List.of("past_due", "unpaid", "incomplete", "paused")) {
            subscriptions = subscription(status, "price_pro");
            assertThat(gateway.entitlement("cus_test")).isEqualTo(new StripeGateway.Entitlement("free", true));
        }
        subscriptions = subscription("canceled", "price_pro");
        assertThat(gateway.entitlement("cus_test")).isEqualTo(new StripeGateway.Entitlement("free", false));
        subscriptions += "," + subscription("active", "price_hobby");
        assertThat(gateway.entitlement("cus_test")).isEqualTo(new StripeGateway.Entitlement("hobby", true));
    }
    @Test void unknownActivePricesFailInsteadOfAcknowledgingAnIncorrectDowngrade() {
        subscriptions = subscription("active", "price_unknown");
        assertThatThrownBy(() -> gateway.entitlement("cus_test")).isInstanceOf(IllegalStateException.class);
    }
    @Test void createsHostedSessionsWithServerSelectedCustomerPriceReturnUrlAndStableIdempotencyKeys() throws Exception {
        assertThat(gateway.customer("123")).isEqualTo("cus_test");
        assertThat(gateway.checkout("cus_test", "price_hobby", "https://api.example", 1).url()).startsWith("https://checkout.stripe.com/");
        assertThat(gateway.portal("cus_test", "https://api.example")).startsWith("https://billing.stripe.com/");
        assertThat(requests.get(0)).contains("account-123", "123");
        assertThat(requests.get(1)).contains("checkout-cus_test-1", "mode=subscription", "customer=cus_test", "price_hobby", "https%3A%2F%2Fapi.example");
        assertThat(requests.get(2)).contains("customer=cus_test", "return_url=");
    }
}
