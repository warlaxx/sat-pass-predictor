package space.nextpass.weather;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import space.nextpass.access.AccessService;
import space.nextpass.access.AccessWebConfiguration;

/** The pass tables' cloud cover: unmetered, cached as long as MET allows, never a 500. */
@WebMvcTest(WeatherController.class)
@Import(AccessWebConfiguration.class)
class WeatherControllerTest {

    static final Instant NOW = Instant.parse("2026-10-07T10:30:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CloudCoverService clouds;

    @MockitoBean
    AccessService access;

    @MockitoBean
    Clock clock;

    @Test
    void servesTheForecastForAsLongAsMetAllowsWithoutSpendingTheQuota() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(clouds.forecast(45.7578, 4.832)).thenReturn(new CloudCoverService.Result(
                new CloudForecast(45.8, 4.8, Instant.parse("2026-10-07T09:21:33Z"),
                        List.of(new CloudForecast.Hour(Instant.parse("2026-10-07T21:00:00Z"), 12, 1))),
                NOW.plusSeconds(1800)));

        mockMvc.perform(get("/api/weather/clouds").param("lat", "45.7578").param("lon", "4.832"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=1800, public"))
                .andExpect(jsonPath("$.latitudeDeg").value(45.8))
                .andExpect(jsonPath("$.hours[0].time").value("2026-10-07T21:00:00Z"))
                .andExpect(jsonPath("$.hours[0].cloudPercent").value(12))
                .andExpect(jsonPath("$.hours[0].stepHours").value(1));

        verifyNoInteractions(access);
    }

    @Test
    void noForecastIsA503TheTableCanIgnore() throws Exception {
        when(clouds.forecast(anyDouble(), anyDouble())).thenThrow(new WeatherUnavailableException("spent"));

        mockMvc.perform(get("/api/weather/clouds").param("lat", "10").param("lon", "20"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.title").value("Weather unavailable"));
    }

    @Test
    void aPlaceOffTheGlobeIsRefusedBeforeMetIsAsked() throws Exception {
        mockMvc.perform(get("/api/weather/clouds").param("lat", "91").param("lon", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/weather/clouds").param("lat", "0").param("lon", "NaN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/weather/clouds").param("lat", "0"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(clouds);
    }
}
