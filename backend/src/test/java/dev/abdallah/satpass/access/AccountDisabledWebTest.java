package dev.abdallah.satpass.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = "account.enabled=false")
@Import(AccountSecurityConfiguration.class)
class AccountDisabledWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    @MockitoBean dev.abdallah.satpass.passes.PassQueryService passes;

    @Test void disabledAccountsCannotStartLoginOrReadData() throws Exception {
        for (String path : new String[]{"/account/", "/account/api/me", "/oauth2/authorization/github"}) {
            mvc.perform(get(path)).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test void demoStillUsesItsOriginalValidationAndVersionedApiFailsClosed() throws Exception {
        mvc.perform(get("/api/passes?noradId=25544&lat=999&lon=4")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/passes")).andExpect(status().isServiceUnavailable());
    }
}
