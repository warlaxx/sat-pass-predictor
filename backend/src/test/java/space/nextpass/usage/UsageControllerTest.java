package space.nextpass.usage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

class UsageControllerTest {

    /** Late in the evening in Paris, already the next day nowhere: the day is UTC's. */
    static final Instant NOW = Instant.parse("2026-10-02T23:30:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Nested
    @WebMvcTest(controllers = UsageController.class)
    @Import(FixedClock.class)
    class WithDatabase {
        @Autowired MockMvc mvc;
        @MockitoBean UsageRepository repository;

        @Test void countsAKnownActionForTheDay() throws Exception {
            mvc.perform(post("/api/usage/event-use-position"))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store"));
            verify(repository).record(UsageEvent.EVENT_USE_POSITION, LocalDate.of(2026, 10, 2));
        }

        @Test void ignoresAnUnknownActionWithoutSayingSo() throws Exception {
            mvc.perform(post("/api/usage/anything-at-all")).andExpect(status().isNoContent());
            verifyNoInteractions(repository);
        }

        @Test void aDatabaseFailureLosesTheCountNotThePage() throws Exception {
            doThrow(new QueryTimeoutException("asleep")).when(repository).record(any(), any());
            mvc.perform(post("/api/usage/list-open-event")).andExpect(status().isNoContent());
        }

        @Test void onlyPostCounts() throws Exception {
            mvc.perform(get("/api/usage/list-open-event")).andExpect(status().isMethodNotAllowed());
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @WebMvcTest(controllers = UsageController.class)
    @Import(FixedClock.class)
    class WithoutDatabase {
        @Autowired MockMvc mvc;

        @Test void answersAsIfCounted() throws Exception {
            mvc.perform(post("/api/usage/list-show-table")).andExpect(status().isNoContent());
        }
    }
}
