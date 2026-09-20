package dev.abdallah.satpass.access;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.abdallah.satpass.api.PassController;
import dev.abdallah.satpass.api.VersionedPassController;
import dev.abdallah.satpass.passes.PassQueryService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {PassController.class, VersionedPassController.class},
        properties = "api-access.allowed-origins=https://customer.example")
@Import(AccessWebConfiguration.class)
class AccessWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccessService access;
    @MockitoBean PassQueryService passes;
    @MockitoBean Clock clock;
    // Invalid latitude: verifies admission runs before validation without needing orbital fixtures.
    private static final String QUERY = "?noradId=25544&lat=999&lon=4";
    @BeforeEach void setup() { when(clock.instant()).thenReturn(Instant.parse("2026-09-19T12:00:30Z")); }

    @Test void bothAliasesAreAccountedBeforeControllerValidation() throws Exception {
        mvc.perform(get("/v1/passes" + QUERY).header("X-API-Key", "key"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        verify(access).admit("key", false);
        mvc.perform(get("/api/passes" + QUERY)).andExpect(status().isBadRequest());
        verify(access).admit(null, true);
        verifyNoInteractions(passes);
    }
    @Test void matrixParametersCannotBypassAdmission() throws Exception {
        doThrow(AccessFailure.unauthorized()).when(access).admit(null, false);
        mvc.perform(get("/v1/passes;ignored=x" + QUERY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(passes);
    }
    @Test void unauthorizedAndRevokedKeysReturnProblemDetails() throws Exception {
        doThrow(AccessFailure.unauthorized()).when(access).admit("revoked", false);
        mvc.perform(get("/v1/passes" + QUERY).header("X-API-Key", "revoked"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/invalid-api-key"));
        verifyNoInteractions(passes);
    }
    @Test void quotaResponseNamesLimitAndResetAndRetryDelay() throws Exception {
        doThrow(new AccessFailure("daily-quota-exceeded", 429, "Daily quota reached",
                Instant.parse("2026-09-20T00:00:00Z"))).when(access).admit(null, true);
        mvc.perform(get("/api/passes" + QUERY)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "43170"))
                .andExpect(jsonPath("$.limit").value("daily"))
                .andExpect(jsonPath("$.resetsAt").value("2026-09-20T00:00:00Z"));
        verifyNoInteractions(passes);
    }
    @Test void databaseFailureFailsClosedWithoutLeakingDetails() throws Exception {
        doThrow(new DataAccessResourceFailureException("secret connection details")).when(access).admit(null, true);
        mvc.perform(get("/api/passes" + QUERY)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("API access accounting is unavailable. Please retry later."));
        verifyNoInteractions(passes);
    }
    @Test void allowedPreflightAndDeniedOriginsDoNotConsumeQuota() throws Exception {
        mvc.perform(options("/v1/passes").header("Origin", "https://customer.example")
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "X-API-Key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://customer.example"));
        mvc.perform(options("/v1/passes").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(access, passes);
    }
}
