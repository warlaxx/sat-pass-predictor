package space.nextpass.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import space.nextpass.images.WikimediaParser.CommonsFile;

class WikimediaParserTest {

    static String fixture(String name) throws IOException {
        try (var in = WikimediaParserTest.class.getResourceAsStream("/images/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void wikidataGivesOneFilePerNoradNumber() throws IOException {
        Map<Integer, String> files = WikimediaParser.sparql(fixture("sparql.json"));

        assertThat(files).containsExactlyInAnyOrderEntriesOf(Map.of(
                20580, "Hubble 2009 close-up.jpg",
                // Two images for the ISS: the first name alphabetically, every night the same.
                25544, "ISS March 2009.jpg",
                // Percent-encoded '+' stays a plus, not a space.
                43013, "C++ sat (model).png",
                44000, "Nc render.jpg"));
        // Not a number, beyond Alpha-5, and not a Commons file: left out.
    }

    @Test
    void commonsGivesThumbnailAuthorAndLicence() throws IOException {
        Map<String, CommonsFile> files = WikimediaParser.commons(fixture("commons.json"));

        CommonsFile hubble = files.get("File:Hubble 2009 close-up.jpg");
        assertThat(hubble.thumbUrl()).isEqualTo(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Hubble_2009_close-up.jpg/500px-Hubble_2009_close-up.jpg");
        assertThat(hubble.thumbWidth()).isEqualTo(500);
        assertThat(hubble.thumbHeight()).isEqualTo(332);
        // Commons' HTML becomes text.
        assertThat(hubble.author()).isEqualTo("NASA & ESA");
        assertThat(hubble.licenceCode()).isEqualTo("pd");
        assertThat(hubble.licence()).isEqualTo("Public domain");
        assertThat(hubble.licenceUrl()).isNull();
        assertThat(hubble.descriptionUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:Hubble_2009_close-up.jpg");

        CommonsFile iss = files.get("File:ISS March 2009.jpg");
        assertThat(iss.author()).isEqualTo("Crew of STS-132");
        assertThat(iss.licence()).isEqualTo("CC BY-SA 4.0");
        assertThat(iss.licenceUrl()).isEqualTo("https://creativecommons.org/licenses/by-sa/4.0");

        // A thumbnail anywhere but upload.wikimedia.org is dropped; so is a missing file.
        assertThat(files).doesNotContainKeys("File:C++ sat (model).png", "File:Deleted.jpg");
        // The licence is not the parser's call.
        assertThat(files.get("File:Nc render.jpg").licenceCode()).isEqualTo("cc-by-nc-2.0");
    }

    /** Wikimedia answers a rate limit with a sentence: the whole fetch fails, nothing is half-read. */
    @Test
    void anythingButJsonFailsWhole() {
        String limited = "You are making too many requests to the API.\nPlease follow the best practices";
        assertThatThrownBy(() -> WikimediaParser.commons(limited))
                .isInstanceOf(ImageSourceException.class)
                .hasMessageContaining("too many requests");
        assertThatThrownBy(() -> WikimediaParser.sparql(limited)).isInstanceOf(ImageSourceException.class);
        assertThatThrownBy(() -> WikimediaParser.commons("{\"error\":{\"code\":\"maxlag\",\"info\":\"Waiting\"}}"))
                .isInstanceOf(ImageSourceException.class)
                .hasMessageContaining("maxlag");
    }

    @Test
    void longAuthorsAreCut() {
        assertThat(WikimediaParser.plain("a".repeat(600), 500)).hasSize(500).endsWith("…");
        assertThat(WikimediaParser.plain("<p> </p>", 500)).isNull();
    }
}
