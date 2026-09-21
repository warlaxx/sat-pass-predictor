package dev.abdallah.satpass.billing;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.stripe.Stripe;
import dev.abdallah.satpass.access.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = BillingController.class, properties = {
    "account.enabled=true", "account.github-client-id=test", "account.github-client-secret=test",
    "billing.enabled=true", "billing.secret-key=sk_test_fake", "billing.webhook-secret=whsec_test",
    "billing.hobby-price=price_hobby", "billing.pro-price=price_pro"})
@Import(AccountSecurityConfiguration.class)
@EnableConfigurationProperties(BillingProperties.class)
class BillingWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean BillingService billing;
    @MockitoBean AccountService accounts;
    @MockitoBean Clock clock;
    private org.springframework.test.web.servlet.request.RequestPostProcessor owner() {
        return oauth2Login().oauth2User(new DefaultOAuth2User(List.of(), Map.of("id", "123"), "id"));
    }
    @Test void checkoutAndPortalRequireSessionAndCsrfAndUseOnlySessionOwner() throws Exception {
        mvc.perform(post("/account/api/billing/checkout?plan=hobby").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/account/api/billing/checkout?plan=hobby").with(owner())).andExpect(status().isForbidden());
        mvc.perform(post("/account/api/billing/portal").with(owner())).andExpect(status().isForbidden());
        when(billing.checkout("123", "hobby")).thenReturn("https://checkout.stripe.com/test");
        mvc.perform(post("/account/api/billing/checkout?plan=hobby&owner=456").with(owner()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://checkout.stripe.com/test"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(billing).checkout("123", "hobby"); verifyNoMoreInteractions(billing);
    }
    String payload(String type) {
        return "{\"id\":\"evt_test\",\"object\":\"event\",\"api_version\":\"" + Stripe.API_VERSION + "\",\"type\":\"" + type
                + "\",\"data\":{\"object\":{\"id\":\"sub_test\",\"object\":\"subscription\",\"customer\":\"cus_test\"}}}";
    }
    String signature(String body, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void onlyAuthenticRecentRawPayloadIsAcceptedWithoutSessionOrCsrf() throws Exception {
        String body = payload("customer.subscription.updated");
        String sig = signature(body, Instant.now().getEpochSecond());
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body).header("Stripe-Signature", sig))
                .andExpect(status().isNoContent());
        verify(billing).reconcile("evt_test", "cus_test");
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body + " ").header("Stripe-Signature", sig))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body)
                .header("Stripe-Signature", signature(body, Instant.now().minusSeconds(600).getEpochSecond())))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(billing);
    }
    @Test void irrelevantEventsAreAcknowledgedAndFailuresAreRetryableWithoutLeakingSecrets() throws Exception {
        String body = payload("unrelated.event");
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body)
                .header("Stripe-Signature", signature(body, Instant.now().getEpochSecond()))).andExpect(status().isNoContent());
        verifyNoInteractions(billing);
        doThrow(new IllegalStateException("secret Stripe key")).when(billing).reconcile(anyString(), anyString());
        body = payload("customer.subscription.deleted");
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body)
                .header("Stripe-Signature", signature(body, Instant.now().getEpochSecond())))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret Stripe key"))));
    }
}
