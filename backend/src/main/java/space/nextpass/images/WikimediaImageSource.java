package space.nextpass.images;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import space.nextpass.images.WikimediaParser.CommonsFile;

/**
 * Photographs from Wikidata and Wikimedia Commons.
 *
 * <h2>Two questions</h2>
 * One SPARQL query asks Wikidata for every item that has both a NORAD number ({@code P377})
 * and an image ({@code P18}): about 1 900 files. Wikidata does not know a file's licence,
 * so Commons is asked next, {@value #BATCH} titles per request — 38 requests a night.
 *
 * <h2>Politely</h2>
 * Wikimedia rate-limits its APIs since 2026: 200 requests a minute for a client that names
 * itself in its User-Agent, ten for one that does not. The requests go one at a time with
 * a pause between them, and a {@code 429} is waited out once, as long as its
 * {@code Retry-After} asks, within a minute. Anything else fails the whole fetch: the
 * previous night's images stand.
 */
public class WikimediaImageSource implements ImageSource {

    static final int BATCH = 50;

    static final String QUERY = "SELECT ?norad ?image WHERE { ?item wdt:P377 ?norad ; wdt:P18 ?image . }";

    /** Wikimedia's standard thumbnail widths include 500; others may be refused or slow. */
    static final int THUMB_WIDTH = 500;

    private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(WikimediaImageSource.class);

    private final RestClient wikidata;
    private final RestClient commons;
    private final Duration pause;

    /**
     * @param wikidata carries the SPARQL endpoint's URL, the timeouts and the User-Agent
     * @param commons  carries {@code api.php}'s URL, the timeouts and the User-Agent
     * @param pause    between two Commons requests
     */
    public WikimediaImageSource(RestClient wikidata, RestClient commons, Duration pause) {
        this.wikidata = wikidata;
        this.commons = commons;
        this.pause = pause;
    }

    @Override
    public List<ObjectImage> fetch() {
        Map<Integer, String> wanted = WikimediaParser.sparql(call("Wikidata", () -> wikidata.get()
                .uri(uri -> uri.queryParam("query", "{query}").build(QUERY))
                .accept(MediaType.valueOf("application/sparql-results+json"))
                .retrieve().body(String.class)));

        List<String> titles = wanted.values().stream().distinct().sorted().map(f -> "File:" + f).toList();
        Map<String, CommonsFile> files = new TreeMap<>();
        for (int from = 0; from < titles.size(); from += BATCH) {
            if (from > 0) {
                sleep(pause);
            }
            List<String> batch = titles.subList(from, Math.min(from + BATCH, titles.size()));
            files.putAll(WikimediaParser.commons(commonsBatch(batch, true)));
        }

        List<ObjectImage> images = new ArrayList<>();
        int refused = 0;
        for (var entry : wanted.entrySet()) {
            CommonsFile file = files.get("File:" + entry.getValue());
            if (file == null || file.descriptionUrl() == null) {
                continue;
            }
            if (!Licences.accepts(file.licenceCode())) {
                refused++;
                continue;
            }
            images.add(new ObjectImage(entry.getKey(), entry.getValue(), file.thumbUrl(), file.thumbWidth(),
                    file.thumbHeight(), file.author(), file.licence() != null ? file.licence() : file.licenceCode(),
                    file.licenceUrl(), file.descriptionUrl()));
        }
        log.info("Wikimedia: {} objects with an image, {} files, {} kept, {} refused for their licence",
                wanted.size(), titles.size(), images.size(), refused);
        return images;
    }

    private String commonsBatch(List<String> titles, boolean mayRetry) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("action", "query");
        form.add("format", "json");
        form.add("formatversion", "2");
        form.add("prop", "imageinfo");
        form.add("iiprop", "url|extmetadata");
        form.add("iiurlwidth", Integer.toString(THUMB_WIDTH));
        form.add("iiextmetadatafilter", "Artist|License|LicenseShortName|LicenseUrl");
        // Asks to be turned away rather than add to a lagging database's load.
        form.add("maxlag", "5");
        form.add("titles", String.join("|", titles));
        Duration[] retryAfter = {null};
        String body = call("Commons", () -> commons.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 429) {
                        retryAfter[0] = retryAfter(response.getHeaders().getFirst("Retry-After"));
                        return null;
                    }
                    if (!status.is2xxSuccessful()) {
                        throw new ImageSourceException("Commons answered " + status);
                    }
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                }));
        if (body != null) {
            return body;
        }
        if (!mayRetry || retryAfter[0] == null) {
            throw new ImageSourceException("Commons is rate-limiting this client (429)");
        }
        log.info("Commons asked to wait {} s", retryAfter[0].toSeconds());
        sleep(retryAfter[0]);
        return commonsBatch(titles, false);
    }

    /** Seconds only; an HTTP date or anything over a minute is not worth waiting for here. */
    static Duration retryAfter(String header) {
        if (header == null || !header.strip().matches("\\d{1,4}")) {
            return null;
        }
        Duration wait = Duration.ofSeconds(Long.parseLong(header.strip()));
        return wait.compareTo(MAX_RETRY_AFTER) <= 0 ? wait : null;
    }

    private static String call(String who, Supplier<String> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new ImageSourceException(who + " unreachable: " + e.getMessage(), e);
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageSourceException("Interrupted while pausing between Commons requests", e);
        }
    }
}
