package dev.abdallah.satpass.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.abdallah.satpass.catalog.CatalogUnavailableException;
import dev.abdallah.satpass.catalog.SatelliteCatalog;
import dev.abdallah.satpass.catalog.SatelliteEntry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.context.annotation.Import({dev.abdallah.satpass.config.TimeConfig.class,
        dev.abdallah.satpass.access.AccessWebConfiguration.class})
@WebMvcTest(SatelliteController.class)
class SatelliteControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SatelliteCatalog catalog;

    @Test
    void servesMatchesAndTheIndexAge() throws Exception {
        when(catalog.search("iss", 10)).thenReturn(new SatelliteCatalog.Result(
                List.of(new SatelliteEntry(25544, "ISS (ZARYA)")), Instant.parse("2026-09-30T12:00:00Z")));

        mockMvc.perform(get("/api/satellites?q=iss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].noradId").value(25544))
                .andExpect(jsonPath("$.results[0].name").value("ISS (ZARYA)"))
                .andExpect(jsonPath("$.catalogFetchedAt").value("2026-09-30T12:00:00Z"));
    }

    @Test
    void refusesMissingOrUnboundedQueriesBeforeSearching() throws Exception {
        mockMvc.perform(get("/api/satellites")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/satellites?q=" + "x".repeat(65))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/satellites?q=iss&limit=26")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalog);
    }

    @Test
    void aTooShortQueryIsARequestError() throws Exception {
        when(catalog.search(anyString(), anyInt())).thenThrow(new IllegalArgumentException("too short"));

        mockMvc.perform(get("/api/satellites?q=s"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(
                        "https://github.com/warlaxx/sat-pass-predictor/errors/invalid-request"));
    }

    @Test
    void anUnavailableCatalogueHasItsOwnType() throws Exception {
        when(catalog.search(anyString(), anyInt())).thenThrow(new CatalogUnavailableException("down"));

        mockMvc.perform(get("/api/satellites?q=iss"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.type").value(
                        "https://github.com/warlaxx/sat-pass-predictor/errors/catalog-unavailable"));
    }
}
