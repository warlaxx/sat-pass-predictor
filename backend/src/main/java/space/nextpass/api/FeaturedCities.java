package space.nextpass.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import space.nextpass.domain.ObserverLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cities whose ISS passes the site computes for free (ABD-34): the pages
 * {@code /iss/:city} ask {@code /api/featured-pass?city=…} as they open.
 *
 * <p>A fixed list, read from {@code iss-cities.json}, so that the unmetered endpoint can
 * compute only fifty places, each cached, and never anywhere a caller likes. The
 * frontend's {@code iss-cities.json} names the same cities with the same coordinates;
 * {@code FeaturedCitiesTest} checks that the two agree.
 */
public final class FeaturedCities {

    static final String RESOURCE = "/iss-cities.json";

    private static final Map<String, ObserverLocation> CITIES = load();

    private FeaturedCities() {}

    public static Optional<ObserverLocation> observer(String slug) {
        return Optional.ofNullable(slug == null ? null : CITIES.get(slug));
    }

    static Map<String, ObserverLocation> all() {
        return CITIES;
    }

    private static Map<String, ObserverLocation> load() {
        try (InputStream in = FeaturedCities.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            Map<String, ObserverLocation> cities = new LinkedHashMap<>();
            for (JsonNode city : JsonMapper.builder().build().readTree(in)) {
                cities.put(city.path("slug").asString(), new ObserverLocation(
                        city.path("lat").asDouble(), city.path("lon").asDouble(), city.path("alt").asDouble()));
            }
            return Map.copyOf(cities);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
