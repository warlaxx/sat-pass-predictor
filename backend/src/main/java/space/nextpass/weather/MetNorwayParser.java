package space.nextpass.weather;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads MET Norway's {@code locationforecast/2.0/compact} answer down to what a pass needs:
 * {@code cloud_area_fraction} at each step. Anything that is not the expected JSON fails
 * the whole answer, so that a half-read forecast is never cached as a whole one.
 */
final class MetNorwayParser {

    record Parsed(Instant updatedAt, List<CloudForecast.Hour> hours) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MetNorwayParser() {}

    /** The steps from an hour before {@code now} to {@code horizon} after it. */
    static Parsed parse(String body, Instant now, Duration horizon) {
        JsonNode properties;
        try {
            properties = JSON.readTree(body == null ? "" : body).path("properties");
        } catch (JacksonException e) {
            throw new WeatherUnavailableException("MET Norway answered something other than JSON", e);
        }
        JsonNode series = properties.path("timeseries");
        if (!series.isArray()) {
            throw new WeatherUnavailableException("MET Norway answered JSON without properties.timeseries");
        }
        List<Instant> times = new ArrayList<>();
        List<Integer> clouds = new ArrayList<>();
        for (JsonNode step : series) {
            JsonNode cloud = step.path("data").path("instant").path("details").path("cloud_area_fraction");
            Instant time = instant(step.path("time").asString(""));
            if (time == null || !cloud.isNumber()) {
                continue;
            }
            times.add(time);
            clouds.add((int) Math.round(Math.max(0, Math.min(100, cloud.asDouble()))));
        }
        Instant from = now.minus(Duration.ofHours(1));
        Instant to = now.plus(horizon);
        List<CloudForecast.Hour> hours = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            Instant time = times.get(i);
            if (time.isBefore(from) || time.isAfter(to)) {
                continue;
            }
            // The last step holds as long as the one before it: MET's own spacing.
            Duration step = i + 1 < times.size() ? Duration.between(time, times.get(i + 1))
                    : i > 0 ? Duration.between(times.get(i - 1), time) : Duration.ofHours(1);
            hours.add(new CloudForecast.Hour(time, clouds.get(i), (int) Math.max(1, step.toHours())));
        }
        return new Parsed(instant(properties.path("meta").path("updated_at").asString("")), List.copyOf(hours));
    }

    private static Instant instant(String text) {
        try {
            return text.isEmpty() ? null : Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
