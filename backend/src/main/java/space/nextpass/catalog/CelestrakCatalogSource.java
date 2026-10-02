package space.nextpass.catalog;

import space.nextpass.domain.TleSnapshot;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Downloads CelesTrak's {@code active} group as OMM, in JSON, and keeps the names.
 *
 * <h2>Why the {@code gp.php} path</h2>
 * The Vercel relay forwards exactly one path, {@code /NORAD/elements/gp.php}, query
 * included. Asking for the group through that same path means the relay serves the index
 * as well as the elements, with no second rewrite to keep in sync.
 *
 * <h2>Why JSON and not the TLE format</h2>
 * The index used to be read from {@code FORMAT=TLE}. That format has no room for the
 * six-digit catalogue numbers given since July 2026, and CelesTrak simply leaves those
 * objects out of it: on 2 October 2026 the TLE form of the group stopped at 69998, with
 * every satellite launched since July missing — the newest Starlink trains included. The
 * OMM forms carry them. JSON rather than CSV, because a name is a quoted string there and
 * a comma in one cannot shift the columns. About 7 MB a day; the records are read one at a
 * time, three fields each, so the whole document is never held as a tree.
 *
 * <h2>All or nothing</h2>
 * Under abuse CelesTrak answers 200 with an HTML page, and a group downloaded twice
 * within its two-hour republication comes back as a sentence. A body in which any record
 * lacks a name or a number is therefore refused as a whole, rather than half indexed: a
 * partial index would silently answer "no such satellite" for names it lost.
 *
 * <h2>The launch</h2>
 * {@code OBJECT_ID} is the international designator, {@code 2026-045A}. Its launch part is
 * kept, as {@code "2026-045"}, so that the satellites of one launch can be found together;
 * an object without one gets no launch, not a refusal.
 *
 * <h2>What is skipped</h2>
 * Numbers above {@link TleSnapshot#MAX_NORAD_ID} are dropped one by one: no TLE can carry
 * them, so the pass endpoint does not accept them, and offering them would only lead to
 * a 400.
 */
public class CelestrakCatalogSource implements CatalogSource {

    /** Every object CelesTrak considers active: payloads, not debris or rocket bodies. */
    static final String GROUP = "active";

    /** {@code 2026-045A}: the launch, then the piece. */
    private static final Pattern INTERNATIONAL_DESIGNATOR = Pattern.compile("(\\d{4}-\\d{3})[A-Z]{1,3}");

    /** Reads the root array one record at a time. */
    private static final ObjectReader OMM = JsonMapper.builder().build().readerFor(Omm.class);

    private final String endpoint;
    private final RestClient restClient;

    public CelestrakCatalogSource(String endpoint, RestClient restClient) {
        this.endpoint = endpoint;
        this.restClient = restClient;
    }

    @Override
    public String endpoint() {
        return endpoint;
    }

    @Override
    public List<SatelliteEntry> fetchAll() {
        return parse(get());
    }

    private String get() {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/NORAD/elements/gp.php")
                            .queryParam("GROUP", GROUP)
                            .queryParam("FORMAT", "JSON")
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new CatalogUnavailableException(
                                endpoint + " answered " + response.getStatusCode()
                                        + " for the satellite catalogue");
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            throw new CatalogUnavailableException(
                    endpoint + " unreachable for the satellite catalogue", e);
        }
    }

    List<SatelliteEntry> parse(String body) {
        if (body == null || body.isBlank()) {
            throw new CatalogUnavailableException(endpoint + " returned an empty catalogue");
        }

        // Keyed by number: a satellite listed twice is one satellite.
        Map<Integer, SatelliteEntry> entries = new LinkedHashMap<>();
        int record = 0;
        try (MappingIterator<Omm> records = OMM.readValues(body)) {
            while (records.hasNext()) {
                Omm omm = records.next();
                record++;
                String name = omm.name() == null ? "" : omm.name().strip();
                if (name.isEmpty() || omm.noradId() == null) {
                    throw new CatalogUnavailableException(endpoint
                            + " returned a catalogue whose record " + record
                            + " has no OBJECT_NAME or no NORAD_CAT_ID");
                }
                int noradId = omm.noradId();
                if (noradId >= 1 && noradId <= TleSnapshot.MAX_NORAD_ID) {
                    entries.putIfAbsent(noradId, new SatelliteEntry(noradId, name, launch(omm.objectId())));
                }
            }
        } catch (JacksonException e) {
            throw new CatalogUnavailableException(endpoint + " returned a catalogue that is not"
                    + " an OMM array (record " + (record + 1) + "): " + e.getOriginalMessage(), e);
        }
        if (entries.isEmpty()) {
            throw new CatalogUnavailableException(
                    endpoint + " returned a catalogue with no usable NORAD number");
        }
        return List.copyOf(entries.values());
    }

    /** {@code "2026-045"} from {@code "2026-045A"}; null when there is no designator. */
    static String launch(String objectId) {
        if (objectId == null) return null;
        Matcher matcher = INTERNATIONAL_DESIGNATOR.matcher(objectId.strip());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** The three fields of an OMM record the index needs; the elements are left alone. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Omm(@JsonProperty("OBJECT_NAME") String name,
               @JsonProperty("OBJECT_ID") String objectId,
               @JsonProperty("NORAD_CAT_ID") Integer noradId) {
    }
}
