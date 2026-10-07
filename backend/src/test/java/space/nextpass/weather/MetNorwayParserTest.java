package space.nextpass.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** MET Norway's answer for Lyon on 7 October 2026, trimmed to six of its 89 steps. */
class MetNorwayParserTest {

    static final Instant NOW = Instant.parse("2026-10-07T10:30:00Z");

    static String fixture(String name) throws IOException {
        try (InputStream in = MetNorwayParserTest.class.getResourceAsStream("/weather/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void keepsTheCloudCoverOfEachStepWithinTheHorizon() throws IOException {
        MetNorwayParser.Parsed parsed = MetNorwayParser.parse(fixture("lyon.json"), NOW, Duration.ofDays(7));

        assertThat(parsed.updatedAt()).isEqualTo(Instant.parse("2026-10-07T09:21:33Z"));
        // 16 October is past the seven days; the hourly steps, then the six-hourly ones.
        assertThat(parsed.hours()).containsExactly(
                new CloudForecast.Hour(Instant.parse("2026-10-07T10:00:00Z"), 100, 1),
                new CloudForecast.Hour(Instant.parse("2026-10-07T11:00:00Z"), 100, 1),
                new CloudForecast.Hour(Instant.parse("2026-10-07T12:00:00Z"), 100, 60),
                new CloudForecast.Hour(Instant.parse("2026-10-10T00:00:00Z"), 95, 6),
                new CloudForecast.Hour(Instant.parse("2026-10-10T06:00:00Z"), 100, 150));
    }

    @Test
    void skipsAStepWithoutCloudsAndRoundsTheRest() {
        String body = """
                {"properties": {"meta": {"updated_at": "2026-10-07T09:00:00Z"}, "timeseries": [
                  {"time": "2026-10-07T11:00:00Z", "data": {"instant": {"details": {"cloud_area_fraction": 12.6}}}},
                  {"time": "2026-10-07T12:00:00Z", "data": {"instant": {"details": {"air_temperature": 14}}}},
                  {"time": "2026-10-07T13:00:00Z", "data": {"instant": {"details": {"cloud_area_fraction": 101}}}}
                ]}}""";

        assertThat(MetNorwayParser.parse(body, NOW, Duration.ofDays(7)).hours()).containsExactly(
                new CloudForecast.Hour(Instant.parse("2026-10-07T11:00:00Z"), 13, 2),
                new CloudForecast.Hour(Instant.parse("2026-10-07T13:00:00Z"), 100, 2));
    }

    @Test
    void anythingButTheExpectedJsonFailsWhole() {
        assertThatThrownBy(() -> MetNorwayParser.parse("Too many requests", NOW, Duration.ofDays(7)))
                .isInstanceOf(WeatherUnavailableException.class);
        assertThatThrownBy(() -> MetNorwayParser.parse("{\"type\": \"Feature\"}", NOW, Duration.ofDays(7)))
                .isInstanceOf(WeatherUnavailableException.class);
    }
}
