package dev.abdallah.satpass.access;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.abdallah.satpass.api.PassController;
import dev.abdallah.satpass.api.VersionedPassController;
import dev.abdallah.satpass.passes.PassQueryService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {PassController.class, VersionedPassController.class},
        properties = "api-access.enabled=false")
@Import(AccessWebConfiguration.class)
class AccessDisabledWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean PassQueryService passes;
    @MockitoBean Clock clock;

    @Test void anonymousDemoStillReachesValidationWithoutDatabase() throws Exception {
        mvc.perform(get("/api/passes?noradId=25544&lat=999&lon=4"))
                .andExpect(status().isBadRequest());
    }

    @Test void versionedAndKeyedCallsFailClosedWithoutDatabase() throws Exception {
        mvc.perform(get("/v1/passes")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/api-access-unavailable"));
        mvc.perform(get("/api/passes").header("X-API-Key", "key"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(passes);
    }
}
