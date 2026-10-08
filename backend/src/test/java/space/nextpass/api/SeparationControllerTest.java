package space.nextpass.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import space.nextpass.access.AccessService;
import space.nextpass.access.AccessWebConfiguration;
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

    /** ABD-15: the RSS feed of the newest separations, in English only. */
    @Nested
    @WebMvcTest(controllers = SeparationController.class)
    @Import({FixedClock.class, AccessWebConfiguration.class})
    class Feed {
        @Autowired MockMvc mvc;
        @MockitoBean SeparationRepository repository;
        /** Would refuse a request without a key, if the feed were ever metered. */
        @MockitoBean AccessService access;

        static Separations.FeedEntry usa667SeenTonight() {
            return new Separations.FeedEntry("S100685", Separations.Kind.RELEASE, usa667().date(),
                    "USA 396", "S60322", "USA 667", 1, Instant.parse("2026-10-02T03:12:00Z"));
        }

        @Test void servesRssWithoutAKeyAndCachesItPublicly() throws Exception {
            when(repository.newest(50)).thenReturn(List.of(usa667SeenTonight()));
            when(repository.updatedAt()).thenReturn(Instant.parse("2026-10-02T03:15:00Z"));

            mvc.perform(get("/api/separations/feed.xml"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/rss+xml;charset=UTF-8"))
                    .andExpect(header().string("Cache-Control", "max-age=900, public"))
                    .andExpect(xpath("/rss/@version").string("2.0"))
                    .andExpect(xpath("/rss/channel/title").string("NextPass · New separations in orbit"))
                    .andExpect(xpath("/rss/channel/lastBuildDate").string("Fri, 2 Oct 2026 03:15:00 GMT"))
                    .andExpect(xpath("count(/rss/channel/item)").number(1.0))
                    .andExpect(xpath("/rss/channel/item[1]/title").string("USA 396 released USA 667"))
                    .andExpect(xpath("/rss/channel/item[1]/link").string("https://www.nextpass.space/separations/S100685"))
                    .andExpect(xpath("/rss/channel/item[1]/pubDate").string("Fri, 2 Oct 2026 03:12:00 GMT"));
            verifyNoInteractions(access);
        }

        @Test void aReaderAskingForPlainXmlStillGetsTheFeed() throws Exception {
            when(repository.newest(50)).thenReturn(List.of());

            mvc.perform(get("/api/separations/feed.xml").header("Accept", "text/xml"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/rss+xml;charset=UTF-8"));
        }

        @Test void feedXmlIsNotTakenForARecord() throws Exception {
            when(repository.newest(50)).thenReturn(List.of());

            mvc.perform(get("/api/separations/feed.xml")).andExpect(status().isOk());
            verify(repository, never()).event(anyString());
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

        @Test void theFeedSaysSoToo() throws Exception {
            mvc.perform(get("/api/separations/feed.xml"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/separations-unavailable"));
        }
    }
}
