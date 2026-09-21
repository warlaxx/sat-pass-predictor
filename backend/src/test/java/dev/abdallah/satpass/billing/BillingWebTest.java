package dev.abdallah.satpass.billing;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import dev.abdallah.satpass.access.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = BillingController.class, properties = {
    "account.enabled=true", "account.github-client-id=test", "account.github-client-secret=test",
    "billing.enabled=true", "billing.secret-key=sk_test_test", "billing.webhook-secret=whsec_test",
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
    @Test void sessionAndCsrfProtectCheckoutAndPortal() throws Exception {
        mvc.perform(post("/account/api/billing/checkout?plan=hobby").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/account/api/billing/checkout?plan=hobby").with(owner())).andExpect(status().isForbidden());
        mvc.perform(post("/account/api/billing/portal").with(owner())).andExpect(status().isForbidden());
        when(billing.checkout("123", "hobby")).thenReturn("https://checkout.stripe.com/test");
        mvc.perform(post("/account/api/billing/checkout?plan=hobby&owner=456").with(owner()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("https://checkout.stripe.com/test"));
        when(billing.portal("123")).thenReturn("https://billing.stripe.com/test");
        mvc.perform(post("/account/api/billing/portal?customer=cus_other").with(owner()).with(csrf())).andExpect(status().isOk());
        verify(billing).checkout("123", "hobby"); verify(billing).portal("123"); verifyNoMoreInteractions(billing);
    }
    String body = "{\"id\":\"evt_1\",\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"customer\":\"cus_1\"}}}";
    String signature(String payload, long time) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + time + ",v1=" + HexFormat.of().formatHex(mac.doFinal((time + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void webhookRequiresValidRecentSignatureButNoSessionOrCsrf() throws Exception {
        long now = Instant.now().getEpochSecond();
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body)
                .header("Stripe-Signature", signature(body, now - 600))).andExpect(status().isBadRequest());
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body + " ")
                .header("Stripe-Signature", signature(body, now))).andExpect(status().isBadRequest());
        verifyNoInteractions(billing);
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body)
                .header("Stripe-Signature", signature(body, now))).andExpect(status().isOk());
        verify(billing).reconcile("evt_1", "cus_1");
    }
    @Test void transientFailureIsRetryableAndSanitized() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret host")).when(billing).reconcile(any(), any());
        mvc.perform(post("/billing/webhook").contentType("application/json").content(body)
                .header("Stripe-Signature", signature(body, Instant.now().getEpochSecond())))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret host"))));
    }
}
