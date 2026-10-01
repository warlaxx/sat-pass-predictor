package space.nextpass.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Downloads CelesTrak's {@code active} group as three-line elements and keeps the names.
 *
 * <h2>Why the {@code gp.php} path and the TLE format</h2>
 * The Vercel relay forwards exactly one path, {@code /NORAD/elements/gp.php}. Asking for
 * the group through that same path means the relay serves the index as well as the
 * elements, with no second rewrite to keep in sync. The TLE format is used rather than CSV
 * because its layout is fixed by NORAD: a name on its own line, the catalogue number in
 * columns 3–7 of line 1, and no quoting rules for names that contain a comma.
 *
 * <h2>All or nothing</h2>
 * Under abuse CelesTrak answers 200 with an HTML page. A body in which any record is not
 * a name followed by lines 1 and 2 is therefore refused as a whole, rather than half
 * indexed: a partial index would silently answer "no such satellite" for names it lost.
 * Checksums are not verified — no prediction is made from these lines, only a number is
 * read from them.
 *
 * <h2>The launch</h2>
 * Columns 10–14 of line 1 hold the year and the launch number of the international
 * designator. They are kept, as {@code "2026-045"}, so that the satellites of one launch
 * can be found together; a line whose columns are not digits gets no launch, not a refusal.
 *
 * <h2>What is skipped</h2>
 * Alpha-5 catalogue numbers (above 99999) are dropped one by one: the pass endpoint does
 * not accept them, so offering them would only lead to a 400.
 */
public class CelestrakCatalogSource implements CatalogSource {

    /** Every object CelesTrak considers active: payloads, not debris or rocket bodies. */
    static final String GROUP = "active";

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
                            .queryParam("FORMAT", "TLE")
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
        List<String> lines = new ArrayList<>();
        for (String raw : (body == null ? "" : body).split("\\R")) {
            String line = raw.stripTrailing();
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new CatalogUnavailableException(endpoint + " returned an empty catalogue");
        }
        if (lines.size() % 3 != 0) {
            throw new CatalogUnavailableException(endpoint + " returned " + lines.size()
                    + " significant lines for the catalogue, not a multiple of 3");
        }

        // Keyed by number: a satellite listed twice is one satellite.
        Map<Integer, SatelliteEntry> entries = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i += 3) {
            String name = name(lines.get(i));
            String line1 = lines.get(i + 1);
            String line2 = lines.get(i + 2);
            if (name.isEmpty() || !line1.startsWith("1 ") || !line2.startsWith("2 ")
                    || line1.length() < 7) {
                throw new CatalogUnavailableException(endpoint
                        + " returned a catalogue whose record " + (i / 3 + 1)
                        + " is not a name followed by TLE lines 1 and 2");
            }
            String number = line1.substring(2, 7).strip();
            if (!number.chars().allMatch(Character::isDigit) || number.isEmpty()) {
                continue; // Alpha-5: see the class javadoc.
            }
            int noradId = Integer.parseInt(number);
            if (noradId >= 1) {
                entries.putIfAbsent(noradId, new SatelliteEntry(noradId, name, launch(line1)));
            }
        }
        if (entries.isEmpty()) {
            throw new CatalogUnavailableException(
                    endpoint + " returned a catalogue with no usable NORAD number");
        }
        return List.copyOf(entries.values());
    }

    /** {@code "2026-045"} from {@code "26045A"}; null when the columns are blank or not digits. */
    static String launch(String line1) {
        if (line1.length() < 14) return null;
        String designator = line1.substring(9, 14);
        if (!designator.chars().allMatch(Character::isDigit)) return null;
        int year = Integer.parseInt(designator.substring(0, 2));
        // The same pivot as the epoch year: Sputnik was launched in 1957.
        return (year < 57 ? 2000 + year : 1900 + year) + "-" + designator.substring(2);
    }

    /** Same rule as the single-TLE parser: the 3LE {@code "0 "} prefix is not part of it. */
    private static String name(String line) {
        String name = line.strip();
        if (name.startsWith("0 ") && !name.substring(2).isBlank()) {
            name = name.substring(2).strip();
        }
        return name;
    }
}
