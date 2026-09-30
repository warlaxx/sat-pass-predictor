package dev.abdallah.satpass.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SatelliteCatalogTest {

    private static final Instant START = Instant.parse("2026-09-30T12:00:00Z");
    private static final Duration REFRESH = Duration.ofHours(24);
    private static final Duration RETRY = Duration.ofMinutes(5);

    private static final List<SatelliteEntry> ENTRIES = List.of(
            new SatelliteEntry(49044, "ISS OBJECT XK"),
            new SatelliteEntry(25544, "ISS (ZARYA)"),
            new SatelliteEntry(20580, "HST"),
            new SatelliteEntry(48274, "CSS (TIANHE)"),
            new SatelliteEntry(44713, "STARLINK-1007"),
            new SatelliteEntry(12345, "CRISS-CROSS"));

    private final TestClock clock = new TestClock(START);

    /** A source answering from a script, counting its calls. */
    private static final class ScriptedSource implements CatalogSource {
        final Deque<Supplier<List<SatelliteEntry>>> answers = new ArrayDeque<>();
        int calls;

        ScriptedSource then(List<SatelliteEntry> entries) {
            answers.add(() -> entries);
            return this;
        }

        ScriptedSource thenFail() {
            answers.add(() -> {
                throw new CatalogUnavailableException("down");
            });
            return this;
        }

        @Override
        public List<SatelliteEntry> fetchAll() {
            calls++;
            return answers.isEmpty() ? ENTRIES : answers.poll().get();
        }

        @Override
        public String endpoint() {
            return "scripted";
        }
    }

    private SatelliteCatalog catalog(CatalogSource... sources) {
        return new SatelliteCatalog(List.of(sources), REFRESH, RETRY, clock);
    }

    private static List<Integer> ids(SatelliteCatalog.Result result) {
        return result.matches().stream().map(SatelliteEntry::noradId).toList();
    }

    @Test
    void ranksPrefixBeforeWordBeforeSubstringAndShortNamesFirst() {
        SatelliteCatalog catalog = catalog(new ScriptedSource());

        // ISS (ZARYA) and ISS OBJECT XK start with it; CRISS-CROSS only contains it.
        assertThat(ids(catalog.search("iss", 10))).containsExactly(25544, 49044, 12345);
        assertThat(ids(catalog.search("tianhe", 10))).containsExactly(48274);
    }

    @Test
    void ignoresCaseAndPunctuation() {
        SatelliteCatalog catalog = catalog(new ScriptedSource());

        assertThat(ids(catalog.search("iss zarya", 10))).containsExactly(25544);
        assertThat(ids(catalog.search("starlink 1007", 10))).containsExactly(44713);
    }

    @Test
    void anExactNumberComesBeforeNamesContainingIt() {
        SatelliteCatalog catalog = catalog(new ScriptedSource().then(List.of(
                new SatelliteEntry(1007, "OTHER"), new SatelliteEntry(44713, "STARLINK-1007"))));

        assertThat(ids(catalog.search("1007", 10))).containsExactly(1007, 44713);
    }

    @Test
    void respectsTheLimitAndReportsTheIndexAge() {
        SatelliteCatalog.Result result = catalog(new ScriptedSource()).search("iss", 2);

        assertThat(ids(result)).containsExactly(25544, 49044);
        assertThat(result.catalogFetchedAt()).isEqualTo(START);
    }

    @Test
    void refusesQueriesTooShortToMeanAnything() {
        SatelliteCatalog catalog = catalog(new ScriptedSource());

        assertThatIllegalArgumentException().isThrownBy(() -> catalog.search("s", 10));
        assertThatIllegalArgumentException().isThrownBy(() -> catalog.search(" ( ", 10));
    }

    @Test
    void downloadsOnceThenAgainOnlyPastRefreshAfter() {
        ScriptedSource source = new ScriptedSource();
        SatelliteCatalog catalog = catalog(source);

        catalog.search("iss", 10);
        clock.advance(REFRESH.minusSeconds(1));
        catalog.search("hst", 10);
        assertThat(source.calls).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(catalog.search("hst", 10).catalogFetchedAt()).isEqualTo(clock.instant());
        assertThat(source.calls).isEqualTo(2);
    }

    @Test
    void aFailedRefreshKeepsTheOldIndexAndBacksOff() {
        ScriptedSource source = new ScriptedSource().then(ENTRIES).thenFail();
        SatelliteCatalog catalog = catalog(source);
        catalog.search("iss", 10);

        clock.advance(REFRESH);
        assertThat(catalog.search("iss", 10).catalogFetchedAt()).isEqualTo(START);
        catalog.search("iss", 10);
        assertThat(source.calls).isEqualTo(2);

        clock.advance(RETRY);
        catalog.search("iss", 10);
        assertThat(source.calls).isEqualTo(3);
    }

    @Test
    void fallsBackToTheNextSource() {
        ScriptedSource origin = new ScriptedSource().thenFail();
        ScriptedSource relay = new ScriptedSource();

        assertThat(ids(catalog(origin, relay).search("hst", 10))).containsExactly(20580);
        assertThat(relay.calls).isEqualTo(1);
    }

    @Test
    void withNothingEverDownloadedSearchIsUnavailableUntilRetryAfter() {
        ScriptedSource source = new ScriptedSource().thenFail();
        SatelliteCatalog catalog = catalog(source);

        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(() -> catalog.search("iss", 10));
        assertThatExceptionOfType(CatalogUnavailableException.class).isThrownBy(() -> catalog.search("iss", 10));
        assertThat(source.calls).isEqualTo(1);

        clock.advance(RETRY);
        assertThat(ids(catalog.search("iss", 10))).startsWith(25544);
    }

    private static final class TestClock extends Clock {
        private Instant instant;

        TestClock(Instant start) {
            instant = start;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
