package space.nextpass.delays;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code satcat_debut} in {@code format/json}: an array of objects whose values are
 * all strings ({@code "NORAD_CAT_ID":"100643"}), as Space-Track writes every class.
 *
 * <p>A row without a usable number or {@code DEBUT} is skipped rather than failing the
 * night: one odd row should cost one data point, not the whole sample. Anything that is
 * not an array — Space-Track reports some errors as a JSON object in a 200 — is an error.
 */
final class SpaceTrackDebutParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SpaceTrackDebutParser() {}

    static List<SpaceTrackDebut> parse(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("satcat_debut answered something that is not JSON", e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("satcat_debut answered " + abbreviate(body)
                    + " instead of a list");
        }
        List<SpaceTrackDebut> debuts = new ArrayList<>(root.size());
        for (JsonNode row : root) {
            Integer noradId = number(row.get("NORAD_CAT_ID"));
            Instant debut = instant(text(row.get("DEBUT")));
            if (noradId == null || noradId <= 0 || debut == null) {
                continue;
            }
            String intldes = text(row.get("INTLDES"));
            String name = text(row.get("SATNAME"));
            debuts.add(new SpaceTrackDebut(noradId, debut,
                    intldes != null ? intldes : text(row.get("OBJECT_ID")),
                    name != null ? name : text(row.get("OBJECT_NAME"))));
        }
        return debuts;
    }

    /** {@code 2026-10-01 18:05:23}, with or without fraction or {@code T}, or a bare date. */
    static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer number(JsonNode node) {
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

    private static String abbreviate(String body) {
        String value = body == null ? "" : body.strip();
        return value.length() <= 120 ? "'" + value + "'" : "'" + value.substring(0, 120) + "…'";
    }
}
