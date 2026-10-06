package space.nextpass.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import space.nextpass.access.AccessService;
import space.nextpass.access.AccessWebConfiguration;
import space.nextpass.domain.ObserverLocation;
import space.nextpass.passes.PassQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The home page's countdown: the default search, unmetered, and not recomputed per visit. */
@WebMvcTest(FeaturedPassController.class)
@Import(AccessWebConfiguration.class)
class FeaturedPassControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T13:52:33Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PassQueryService passQueryService;

    @MockitoBean
    AccessService access;

    @MockitoBean
    Clock clock;

    @Test
    void servesTheDefaultSearchWithoutSpendingTheDemoQuota() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble())).thenReturn(PassFixtures.prediction());

        mockMvc.perform(get("/api/featured-pass"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.satellite.noradId").value(25544))
                .andExpect(jsonPath("$.passes[0].aos.instant").value("2026-09-22T19:18:54Z"));

        verify(passQueryService).findPasses(25544, PassFixtures.LYON, Duration.ofHours(48), 10.0);
        verifyNoInteractions(access);
    }

    /** An hour after the other test: the controller is shared, and so is what it keeps. */
    @Test
    void reusesItsAnswerForAMinute() throws Exception {
        Instant later = NOW.plusSeconds(3600);
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble())).thenReturn(PassFixtures.prediction());

        when(clock.instant()).thenReturn(later);
        mockMvc.perform(get("/api/featured-pass")).andExpect(status().isOk());
        when(clock.instant()).thenReturn(later.plusSeconds(59));
        mockMvc.perform(get("/api/featured-pass")).andExpect(status().isOk());
        verify(passQueryService, times(1)).findPasses(anyInt(), any(), any(), anyDouble());

        when(clock.instant()).thenReturn(later.plusSeconds(60));
        mockMvc.perform(get("/api/featured-pass")).andExpect(status().isOk());
        verify(passQueryService, times(2)).findPasses(anyInt(), any(), any(), anyDouble());
    }

    /** ABD-34: a featured city, at its own coordinates, still unmetered. */
    @Test
    void servesAFeaturedCity() throws Exception {
        when(clock.instant()).thenReturn(NOW.plusSeconds(7200));
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble())).thenReturn(PassFixtures.prediction());

        mockMvc.perform(get("/api/featured-pass").param("city", "paris"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"));

        verify(passQueryService).findPasses(25544, new ObserverLocation(48.8566, 2.3522, 35), Duration.ofHours(48), 10.0);
        verifyNoInteractions(access);
    }

    /** Only the fifty places: anything else would compute for free wherever a caller likes. */
    @Test
    void refusesAnUnknownCity() throws Exception {
        mockMvc.perform(get("/api/featured-pass").param("city", "atlantis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown city"));
        verifyNoInteractions(passQueryService);
    }
}
