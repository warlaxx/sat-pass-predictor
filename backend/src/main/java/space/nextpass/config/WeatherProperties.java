package space.nextpass.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the cloud cover comes from (ABD-36), and how much of it may be asked.
 *
 * @param metUrl            MET Norway's {@code locationforecast/2.0/compact}
 * @param upstreamPerMinute cells fetched from MET at most each minute, whatever is asked
 * @param maxCells          forecasts kept in memory at most
 * @param connectTimeout    budget to open a connection
 * @param readTimeout       budget for the answer: the table shows its passes meanwhile
 */
@ConfigurationProperties("weather")
public record WeatherProperties(String metUrl, Integer upstreamPerMinute, Integer maxCells,
                                Duration connectTimeout, Duration readTimeout) {

    public WeatherProperties {
        if (metUrl == null || metUrl.isBlank()) {
            throw new IllegalArgumentException("weather.met-url must be set");
        }
        upstreamPerMinute = upstreamPerMinute == null ? 60 : upstreamPerMinute;
        maxCells = maxCells == null ? 5000 : maxCells;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
}
