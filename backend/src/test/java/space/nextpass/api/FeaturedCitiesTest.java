package space.nextpass.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import space.nextpass.domain.ObserverLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FeaturedCitiesTest {

    /** The frontend's list, which names the pages; run from backend/, it sits next door. */
    static final Path FRONTEND_CITIES = Path.of("../frontend/src/app/pages/iss-city/iss-cities.json");

    @Test
    void fiftyCitiesWithTheirCoordinates() {
        assertThat(FeaturedCities.all()).hasSize(50);
        assertThat(FeaturedCities.observer("paris")).contains(new ObserverLocation(48.8566, 2.3522, 35));
        assertThat(FeaturedCities.observer("atlantis")).isEmpty();
        assertThat(FeaturedCities.observer(null)).isEmpty();
    }

    /** The pages and the endpoint must mean the same place by the same name. */
    @Test
    void theFrontendNamesTheSameCitiesAtTheSameCoordinates() throws Exception {
        if (!Files.exists(FRONTEND_CITIES)) {
            return; // A backend-only checkout has nothing to compare with.
        }
        JsonNode frontend = JsonMapper.builder().build().readTree(Files.readString(FRONTEND_CITIES));
        assertThat(frontend.size()).isEqualTo(FeaturedCities.all().size());
        for (JsonNode city : frontend) {
            String slug = city.path("slug").asString();
            assertThat(FeaturedCities.observer(slug)).as(slug).contains(new ObserverLocation(
                    city.path("lat").asDouble(), city.path("lon").asDouble(), city.path("alt").asDouble()));
        }
    }
}
