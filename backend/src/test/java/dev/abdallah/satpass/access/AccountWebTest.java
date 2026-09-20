package dev.abdallah.satpass.access;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AccountController.class, AccountPageController.class}, properties = {
        "account.enabled=true", "account.github-client-id=test-client", "account.github-client-secret=test-secret"})
@Import(AccountSecurityConfiguration.class)
class AccountWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountService accounts;
    @MockitoBean Clock clock;

    private org.springframework.test.web.servlet.request.RequestPostProcessor owner() {
        return oauth2Login().oauth2User(new DefaultOAuth2User(List.of(), Map.of("id", 123, "login", "mutable-name"), "id"));
    }

    @Test void landingPageAndAssetsArePublicWithSecurityHeaders() throws Exception {
        mvc.perform(get("/account/")).andExpect(status().isOk())
                .andExpect(forwardedUrl("/account/index.html"));
        mvc.perform(get("/account/index.html")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(content().string(containsString("Continue with GitHub")));
        mvc.perform(get("/account/dashboard.js")).andExpect(status().isOk());
    }

    @Test void accountDataRequiresSessionAndDoesNotAcceptAnApiKey() throws Exception {
        mvc.perform(get("/account/api/me").header("X-API-Key", "spp_" + "a".repeat(43)))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(accounts);
    }

    @Test void mutationsRequireCsrfEvenWithAValidSession() throws Exception {
        mvc.perform(post("/account/api/key").with(owner())).andExpect(status().isForbidden());
        mvc.perform(delete("/account/api/key").with(owner())).andExpect(status().isForbidden());
        mvc.perform(post("/account/logout").with(owner())).andExpect(status().isForbidden());
        verifyNoInteractions(accounts);
    }

    @Test void onlyAuthenticatedImmutableIdentityCanManageItsKey() throws Exception {
        var key = new AccessService.IssuedKey(UUID.randomUUID(), "spp_" + "a".repeat(43));
        when(accounts.regenerate("123")).thenReturn(key);
        mvc.perform(post("/account/api/key?githubId=456").with(owner()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.secret").value(key.secret()))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(delete("/account/api/key?githubId=456").with(owner()).with(csrf())).andExpect(status().isNoContent());
        verify(accounts).regenerate("123"); verify(accounts).revoke("123");
        verifyNoMoreInteractions(accounts);
    }

    @Test void dashboardDoesNotReturnTheSecretAndCsrfCanBeRead() throws Exception {
        when(accounts.dashboard("123")).thenReturn(new AccountService.Dashboard(UUID.randomUUID(), "standard", true, 7, 100, 10, LocalDate.of(2026, 9, 20)));
        mvc.perform(get("/account/api/me").with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.usedToday").value(7)).andExpect(jsonPath("$.secret").doesNotExist());
        mvc.perform(get("/account/api/csrf").with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty()).andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"));
    }

    @Test void oauthUsesConfiguredCallbackAndStateAndRejectsUnsolicitedCallback() throws Exception {
        mvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", allOf(org.hamcrest.Matchers.startsWith("https://github.com/login/oauth/authorize?"),
                        containsString("state="), containsString("redirect_uri=http://localhost:8080/login/oauth2/code/github"))));
        mvc.perform(get("/login/oauth2/code/github?code=untrusted&state=wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:8080/account/?login=failed"));
        verifyNoInteractions(accounts);
    }

    @Test void logoutAcceptsOnlyProtectedPostAndDatabaseErrorsAreSanitized() throws Exception {
        mvc.perform(post("/account/logout").with(owner()).with(csrf())).andExpect(status().isNoContent());
        when(accounts.dashboard("123")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret database URL"));
        mvc.perform(get("/account/api/me").with(owner())).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("secret database URL"))));
    }
}
