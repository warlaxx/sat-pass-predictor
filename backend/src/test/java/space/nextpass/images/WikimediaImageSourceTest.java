package space.nextpass.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WikimediaImageSourceTest {

    MockRestServiceServer wikidataServer;
    MockRestServiceServer commonsServer;
    WikimediaImageSource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder wikidata = RestClient.builder().baseUrl("https://query.wikidata.org/sparql");
        RestClient.Builder commons = RestClient.builder().baseUrl("https://commons.wikimedia.org/w/api.php");
        wikidataServer = MockRestServiceServer.bindTo(wikidata).build();
        commonsServer = MockRestServiceServer.bindTo(commons).build();
        source = new WikimediaImageSource(wikidata.build(), commons.build(), Duration.ZERO);
    }

    @Test
    void keepsTheFreeImagesWithTheirCredit() throws IOException {
        wikidataServer.expect(method(HttpMethod.GET))
                .andExpect(requestTo(org.hamcrest.Matchers.containsString("P377")))
                .andRespond(withSuccess(WikimediaParserTest.fixture("sparql.json"), MediaType.APPLICATION_JSON));
        commonsServer.expect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of("prop", "imageinfo", "iiurlwidth", "500")))
                .andRespond(withSuccess(WikimediaParserTest.fixture("commons.json"), MediaType.APPLICATION_JSON));

        List<ObjectImage> images = source.fetch();

        // The NonCommercial render and the off-site thumbnail are dropped.
        assertThat(images).extracting(ObjectImage::noradId).containsExactly(20580, 25544);
        ObjectImage hubble = images.getFirst();
        assertThat(hubble.file()).isEqualTo("Hubble 2009 close-up.jpg");
        assertThat(hubble.author()).isEqualTo("NASA & ESA");
        assertThat(hubble.licence()).isEqualTo("Public domain");
        wikidataServer.verify();
        commonsServer.verify();
    }

    /** A rate limit asking for longer than a minute fails the night; the stored images stand. */
    @Test
    void aLongRateLimitFailsTheFetch() throws IOException {
        wikidataServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess(WikimediaParserTest.fixture("sparql.json"), MediaType.APPLICATION_JSON));
        HttpHeaders retry = new HttpHeaders();
        retry.set("Retry-After", "3600");
        commonsServer.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retry));

        assertThatThrownBy(source::fetch).isInstanceOf(ImageSourceException.class).hasMessageContaining("429");
    }

    /** A short one is waited out, once. */
    @Test
    void aShortRateLimitIsWaitedOutOnce() throws IOException {
        wikidataServer.expect(method(HttpMethod.GET))
                .andRespond(withSuccess(WikimediaParserTest.fixture("sparql.json"), MediaType.APPLICATION_JSON));
        HttpHeaders retry = new HttpHeaders();
        retry.set("Retry-After", "0");
        commonsServer.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retry));
        commonsServer.expect(method(HttpMethod.POST))
                .andRespond(withSuccess(WikimediaParserTest.fixture("commons.json"), MediaType.APPLICATION_JSON));

        assertThat(source.fetch()).hasSize(2);
    }

    @Test
    void anUnreachableWikidataFailsTheFetch() {
        wikidataServer.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(source::fetch).isInstanceOf(ImageSourceException.class).hasMessageContaining("Wikidata");
    }

    @Test
    void retryAfterIsSecondsWithinAMinute() {
        assertThat(WikimediaImageSource.retryAfter("30")).isEqualTo(Duration.ofSeconds(30));
        assertThat(WikimediaImageSource.retryAfter("61")).isNull();
        assertThat(WikimediaImageSource.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
        assertThat(WikimediaImageSource.retryAfter(null)).isNull();
    }
}
