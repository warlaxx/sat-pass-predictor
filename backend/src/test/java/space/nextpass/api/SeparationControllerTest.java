package space.nextpass.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import space.nextpass.separations.SeparationRepository;
import space.nextpass.separations.Separations;

class SeparationControllerTest {

    static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static Separations.Summary usa667() {
        return new Separations.Summary("S100685", Separations.Kind.RELEASE,
                new Separations.Date("2026 Sep?", Instant.parse("2026-09-01T00:00:00Z"), "MONTH", true),
                "USA 396", "AFRL", "US", "USA 667", 100685, 1,
                new Separations.Orbit(35800.0, 36100.0, 0.0, "GEO/D"), true);
    }

    @Nested
    @WebMvcTest(controllers = SeparationController.class)
    @Import(FixedClock.class)
    class WithDatabase {
        @Autowired MockMvc mvc;
        @MockitoBean SeparationRepository repository;

        @Test void listsEventsWithStatsAndCachesThem() throws Exception {
            when(repository.latest(null, 50)).thenReturn(List.of(usa667()));
            when(repository.stats(NOW)).thenReturn(new Separations.Stats(28364, 105, 2026,
                    List.of(new Separations.Month("2026-09", 2, 0))));
            when(repository.updatedAt()).thenReturn(Instant.parse("2026-10-02T16:42:00Z"));

            mvc.perform(get("/api/separations"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=900, public"))
                    .andExpect(jsonPath("$.events[0].id").value("S100685"))
                    .andExpect(jsonPath("$.events[0].kind").value("RELEASE"))
                    .andExpect(jsonPath("$.events[0].date.text").value("2026 Sep?"))
                    .andExpect(jsonPath("$.events[0].date.precision").value("MONTH"))
                    .andExpect(jsonPath("$.stats.byMonth[0].releases").value(2))
                    .andExpect(jsonPath("$.updatedAt").value("2026-10-02T16:42:00Z"));
        }

        @Test void filtersByKind() throws Exception {
            when(repository.latest(Separations.Kind.FRAGMENTATION, 10)).thenReturn(List.of());
            when(repository.stats(any())).thenReturn(new Separations.Stats(0, 0, 2026, List.of()));

            mvc.perform(get("/api/separations?kind=fragmentation&limit=10")).andExpect(status().isOk());
        }

        @Test void anUnknownKindIsABadRequest() throws Exception {
            mvc.perform(get("/api/separations?kind=explosion")).andExpect(status().isBadRequest());
            verifyNoInteractions(repository);
        }

        @Test void findsAnEventByAnyRecordIgnoringCase() throws Exception {
            when(repository.event("S100685")).thenReturn(Optional.of(new Separations.Event("S100685",
                    Separations.Kind.RELEASE, usa667().date(), null, null, List.of(), 1, NOW)));

            mvc.perform(get("/api/separations/s100685"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("S100685"))
                    .andExpect(jsonPath("$.childCount").value(1));
        }

        @Test void anUnknownOrMalformedRecordIsNotFound() throws Exception {
            when(repository.event("S1")).thenReturn(Optional.empty());

            mvc.perform(get("/api/separations/S1"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/separation-not-found"));
            mvc.perform(get("/api/separations/not-a-record")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @WebMvcTest(controllers = SeparationController.class)
    @Import(FixedClock.class)
    class WithoutDatabase {
        @Autowired MockMvc mvc;

        @Test void saysSoRatherThanAnsweringAnEmptyList() throws Exception {
            mvc.perform(get("/api/separations"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/separations-unavailable"));
            mvc.perform(get("/api/separations/S100685")).andExpect(status().isServiceUnavailable());
        }
    }
}
