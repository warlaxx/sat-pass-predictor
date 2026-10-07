package space.nextpass.weather;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The cloud cover over a place, from MET Norway's Locationforecast (ABD-36), kept so that
 * the passes of a whole street cost MET one request an hour.
 *
 * <h2>Why MET Norway, not Open-Meteo</h2>
 * Open-Meteo's free API excludes "websites or apps that have subscriptions or display
 * advertisements", which NextPass does. MET Norway's data is CC BY 4.0, commercial use
 * included, against a credit, an identifying User-Agent and a cache that honours
 * {@code Expires} and {@code If-Modified-Since} (https://api.met.no/doc/TermsOfService).
 *
 * <h2>One cell, one entry</h2>
 * Places are rounded to {@link #CELL_DEG} - about 11 km, finer than the forecast's own grid
 * outside Scandinavia - and the rounded place is what MET is asked, so that neighbours
 * share an entry and the observer's exact position never leaves the server.
 *
 * <h2>A budget, because the endpoint is open</h2>
 * Anyone can ask for any place, and MET bans an application that goes over 20 requests a
 * second. At most {@code upstreamPerMinute} cells are fetched each minute; past it, a kept
 * forecast is served even if it has expired, and a cell never fetched is unavailable. The
 * cache holds at most {@code maxCells} entries.
 */
public class CloudCoverService {

    static final double CELL_DEG = 0.1;
    static final Duration HORIZON = Duration.ofDays(7);
    /** Bounds on MET's {@code Expires}: never refetch sooner, never keep an answer longer. */
    static final Duration MIN_KEEP = Duration.ofMinutes(5);
    static final Duration MAX_KEEP = Duration.ofHours(2);
    static final Duration DEFAULT_KEEP = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(CloudCoverService.class);

    /** What a caller gets: the forecast, and until when it may be reused. */
    public record Result(CloudForecast forecast, Instant expires) {}

    record Cell(double latitudeDeg, double longitudeDeg) {
        static Cell of(double latitudeDeg, double longitudeDeg) {
            // Longitude 180 and -180 are one meridian; MET accepts -180 to 180.
            double lon = Math.round(longitudeDeg / CELL_DEG) * CELL_DEG;
            return new Cell(round(Math.round(latitudeDeg / CELL_DEG) * CELL_DEG), round(lon >= 180 ? lon - 360 : lon));
        }

        private static double round(double value) {
            return Math.round(value * 10) / 10.0;
        }

        String latitude() {
            return String.format(Locale.ROOT, "%.1f", latitudeDeg);
        }

        String longitude() {
            return String.format(Locale.ROOT, "%.1f", longitudeDeg);
        }
    }

    private record Entry(CloudForecast forecast, Instant expires, String lastModified) {}

    private final RestClient met;
    private final Clock clock;
    private final int upstreamPerMinute;
    private final int maxCells;
    private final Map<Cell, Entry> cache = new ConcurrentHashMap<>();
    private Instant windowStart = Instant.EPOCH;
    private int windowCount;

    public CloudCoverService(RestClient met, Clock clock, int upstreamPerMinute, int maxCells) {
        this.met = met;
        this.clock = clock;
        this.upstreamPerMinute = upstreamPerMinute;
        this.maxCells = maxCells;
    }

    public Result forecast(double latitudeDeg, double longitudeDeg) {
        Instant now = clock.instant();
        Cell cell = Cell.of(latitudeDeg, longitudeDeg);
        Entry kept = cache.get(cell);
        if (kept != null && now.isBefore(kept.expires())) {
            return new Result(kept.forecast(), kept.expires());
        }
        if (!spend(now)) {
            if (kept != null) {
                return new Result(kept.forecast(), now.plus(MIN_KEEP));
            }
            throw new WeatherUnavailableException("The weather budget for this minute is spent");
        }
        try {
            Entry fresh = fetch(cell, kept, now);
            remember(cell, fresh, now);
            return new Result(fresh.forecast(), fresh.expires());
        } catch (RestClientException | WeatherUnavailableException e) {
            log.warn("MET Norway failed for {},{}: {}", cell.latitude(), cell.longitude(), e.getMessage());
            if (kept == null) {
                throw e instanceof WeatherUnavailableException w ? w
                        : new WeatherUnavailableException("MET Norway is unreachable", e);
            }
            // The last good forecast, and a pause before asking again.
            Entry stale = new Entry(kept.forecast(), now.plus(MIN_KEEP), kept.lastModified());
            cache.put(cell, stale);
            return new Result(stale.forecast(), stale.expires());
        }
    }

    private Entry fetch(Cell cell, Entry kept, Instant now) {
        ResponseEntity<String> response = met.get()
                .uri(uri -> uri.queryParam("lat", cell.latitude()).queryParam("lon", cell.longitude()).build())
                .headers(headers -> {
                    if (kept != null && kept.lastModified() != null) {
                        headers.set(HttpHeaders.IF_MODIFIED_SINCE, kept.lastModified());
                    }
                })
                .retrieve()
                .toEntity(String.class);
        Instant expires = expires(response.getHeaders(), now);
        String lastModified = response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED) && kept != null) {
            return new Entry(kept.forecast(), expires, lastModified != null ? lastModified : kept.lastModified());
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new WeatherUnavailableException("MET Norway answered " + response.getStatusCode());
        }
        MetNorwayParser.Parsed parsed = MetNorwayParser.parse(response.getBody(), now, HORIZON);
        return new Entry(new CloudForecast(cell.latitudeDeg(), cell.longitudeDeg(), parsed.updatedAt(), parsed.hours()),
                expires, lastModified);
    }

    private static Instant expires(HttpHeaders headers, Instant now) {
        long millis = headers.getExpires();
        Instant expires = millis < 0 ? now.plus(DEFAULT_KEEP) : Instant.ofEpochMilli(millis);
        if (expires.isBefore(now.plus(MIN_KEEP))) {
            return now.plus(MIN_KEEP);
        }
        return expires.isAfter(now.plus(MAX_KEEP)) ? now.plus(MAX_KEEP) : expires;
    }

    private synchronized boolean spend(Instant now) {
        if (!now.isBefore(windowStart.plus(Duration.ofMinutes(1))) || now.isBefore(windowStart)) {
            windowStart = now;
            windowCount = 0;
        }
        if (windowCount >= upstreamPerMinute) {
            return false;
        }
        windowCount++;
        return true;
    }

    private void remember(Cell cell, Entry entry, Instant now) {
        cache.put(cell, entry);
        if (cache.size() <= maxCells) {
            return;
        }
        cache.values().removeIf(e -> !now.isBefore(e.expires()));
        // Still full of live entries: drop some, any; they are an hour's worth at most.
        Iterator<Cell> cells = cache.keySet().iterator();
        while (cache.size() > maxCells && cells.hasNext()) {
            Cell next = cells.next();
            if (!next.equals(cell)) {
                cells.remove();
            }
        }
    }

    int cachedCells() {
        return cache.size();
    }
}
