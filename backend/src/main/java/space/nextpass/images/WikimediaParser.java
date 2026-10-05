package space.nextpass.images;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the two answers {@link WikimediaImageSource} asks for. Both fail whole on anything
 * that is not the expected JSON: Wikimedia answers a rate limit with a sentence of plain
 * text, and half a list would delete the other half's images.
 */
final class WikimediaParser {

    /** What Commons says about one file. {@code licenceCode} is the machine code {@link Licences} reads. */
    record CommonsFile(String title, String thumbUrl, Integer thumbWidth, Integer thumbHeight, String author,
                       String licenceCode, String licence, String licenceUrl, String descriptionUrl) {}

    /** {@code TleSnapshot.MAX_NORAD_ID}: Alpha-5 ends at Z9999. */
    static final int MAX_NORAD_ID = 339_999;

    static final String THUMB_HOST = "upload.wikimedia.org";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final String FILE_PATH = "/wiki/Special:FilePath/";

    private WikimediaParser() {}

    /**
     * The Wikidata answer, as NORAD number to Commons file name ({@code Hubble 2009
     * close-up.jpg}). An object with several images keeps the first file name in
     * alphabetical order, so that the choice does not change from one night to the next.
     */
    static Map<Integer, String> sparql(String body) {
        JsonNode bindings = read(body, "Wikidata").path("results").path("bindings");
        if (!bindings.isArray()) {
            throw new ImageSourceException("Wikidata answered JSON without results.bindings");
        }
        Map<Integer, String> files = new TreeMap<>();
        for (JsonNode row : bindings) {
            Integer norad = norad(row.path("norad").path("value").asString(""));
            String file = file(row.path("image").path("value").asString(""));
            if (norad == null || file == null) {
                continue;
            }
            files.merge(norad, file, (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        return files;
    }

    /**
     * One Commons {@code imageinfo} answer, by the title it was asked for ({@code
     * File:Hubble_2009_close-up.jpg}): Commons normalises titles, and says how in
     * {@code query.normalized}. Missing files are left out; so are thumbnails anywhere but
     * on {@value #THUMB_HOST}, which the site's rewrite serves.
     */
    static Map<String, CommonsFile> commons(String body) {
        JsonNode root = read(body, "Commons");
        if (root.has("error")) {
            throw new ImageSourceException("Commons answered an error: "
                    + root.path("error").path("code").asString("?") + " "
                    + abbreviate(root.path("error").path("info").asString("")));
        }
        JsonNode query = root.path("query");
        JsonNode pages = query.path("pages");
        if (!pages.isArray()) {
            throw new ImageSourceException("Commons answered JSON without query.pages");
        }
        Map<String, String> asked = new HashMap<>();
        for (JsonNode n : query.path("normalized")) {
            asked.put(n.path("to").asString(""), n.path("from").asString(""));
        }
        Map<String, CommonsFile> files = new HashMap<>();
        for (JsonNode page : pages) {
            JsonNode info = page.path("imageinfo").path(0);
            if (page.path("missing").asBoolean(false) || info.isMissingNode()) {
                continue;
            }
            String title = page.path("title").asString("");
            String thumb = text(info.path("thumburl"));
            if (thumb == null || !onThumbHost(thumb)) {
                continue;
            }
            JsonNode meta = info.path("extmetadata");
            files.put(asked.getOrDefault(title, title), new CommonsFile(
                    title,
                    thumb,
                    integer(info.path("thumbwidth")),
                    integer(info.path("thumbheight")),
                    plain(text(meta.path("Artist").path("value")), 500),
                    text(meta.path("License").path("value")),
                    plain(text(meta.path("LicenseShortName").path("value")), 64),
                    text(meta.path("LicenseUrl").path("value")),
                    text(info.path("descriptionurl"))));
        }
        return files;
    }

    /** {@code http://commons.wikimedia.org/wiki/Special:FilePath/Hubble%202009%20close-up.jpg} → the name. */
    static String file(String url) {
        int at = url.indexOf(FILE_PATH);
        if (at < 0) {
            return null;
        }
        String name = URLDecoder.decode(url.substring(at + FILE_PATH.length()).replace("+", "%2B"),
                StandardCharsets.UTF_8).replace('_', ' ').strip();
        return name.isEmpty() || name.length() > 255 ? null : name;
    }

    /** Commons' Artist field is HTML ({@code <a href="…">NASA</a>}); the page shows text. */
    static String plain(String html, int max) {
        if (html == null) {
            return null;
        }
        String text = SPACE.matcher(TAG.matcher(html).replaceAll(" ")).replaceAll(" ").strip()
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1).strip() + "…";
    }

    private static boolean onThumbHost(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && THUMB_HOST.equals(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Integer norad(String value) {
        if (!value.matches("\\d{1,6}")) {
            return null;
        }
        int n = Integer.parseInt(value);
        return n > 0 && n <= MAX_NORAD_ID ? n : null;
    }

    private static JsonNode read(String body, String who) {
        try {
            JsonNode root = JSON.readTree(body == null ? "" : body);
            if (root == null || !root.isObject()) {
                throw new ImageSourceException(who + " answered " + abbreviate(body) + " instead of JSON");
            }
            return root;
        } catch (JacksonException e) {
            throw new ImageSourceException(who + " answered " + abbreviate(body) + " instead of JSON", e);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString("").strip();
        return value.isEmpty() ? null : value;
    }

    private static Integer integer(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "nothing";
        }
        String line = SPACE.matcher(body).replaceAll(" ").strip();
        return "\"" + (line.length() <= 120 ? line : line.substring(0, 120) + "…") + "\"";
    }
}
