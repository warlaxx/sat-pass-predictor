package space.nextpass.gcat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads a GCAT {@code .tsv} catalogue one line at a time.
 *
 * <p>Streaming, not "read the file then split": {@code satcat.tsv} is 19 MB of text and
 * the free Render instance has 512 MB for everything, Orekit included.
 *
 * <p>Columns are found by the names in the {@code #JCAT …} header, never by position, so
 * a column McDowell adds or moves does not shift every value by one. A missing column the
 * import needs is an error: better a failed night than a silently empty field.
 *
 * <p>Cells are padded with spaces and GCAT writes "none" as {@code -}; both become
 * {@code null}. A date or number that does not parse keeps its text and is counted in
 * {@link Result#unparsed()} rather than failing the file over one cell.
 */
public final class GcatParser {

    public record Result(int rows, int unparsed) {}

    private static final String[] REQUIRED = {
            "JCAT", "Satcat", "Launch_Tag", "Piece", "Type", "Name", "PLName", "LDate",
            "Parent", "SDate", "Primary", "DDate", "Status", "Owner", "State", "Mass",
            "Perigee", "Apogee", "Inc", "OpOrbit", "AltNames"};

    private GcatParser() {}

    public static Result parse(BufferedReader reader, Consumer<GcatObject> sink) {
        try {
            Map<String, Integer> columns = null;
            int rows = 0;
            int[] unparsed = {0};
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    if (columns == null && line.startsWith("#JCAT")) {
                        columns = header(line);
                    }
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                if (columns == null) {
                    throw new GcatImportException("GCAT file has data before its #JCAT header");
                }
                sink.accept(row(line.split("\t", -1), columns, unparsed));
                rows++;
            }
            if (columns == null) {
                throw new GcatImportException("GCAT file has no #JCAT header");
            }
            return new Result(rows, unparsed[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Integer> header(String line) {
        String[] names = line.substring(1).split("\t", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < names.length; i++) {
            columns.put(names[i].strip(), i);
        }
        for (String name : REQUIRED) {
            if (!columns.containsKey(name)) {
                throw new GcatImportException("GCAT header lacks column " + name);
            }
        }
        return columns;
    }

    private static GcatObject row(String[] cells, Map<String, Integer> columns, int[] unparsed) {
        var cell = new Object() {
            String text(String column) {
                int index = columns.get(column);
                if (index >= cells.length) {
                    return null;
                }
                String value = cells[index].strip();
                return value.isEmpty() || value.equals("-") ? null : value;
            }

            GcatDate date(String text) {
                try {
                    return GcatDate.parse(text);
                } catch (IllegalArgumentException e) {
                    unparsed[0]++;
                    return null;
                }
            }

            Double number(String column) {
                String value = text(column);
                if (value == null) {
                    return null;
                }
                if (value.equals("Inf")) {
                    // An apogee of "Inf": the object left Earth orbit. A fact, not a gap.
                    return Double.POSITIVE_INFINITY;
                }
                try {
                    return Double.valueOf(value);
                } catch (NumberFormatException e) {
                    unparsed[0]++;
                    return null;
                }
            }
        };
        String jcat = cell.text("JCAT");
        if (jcat == null) {
            throw new GcatImportException("GCAT row without JCAT");
        }
        String satcat = cell.text("Satcat");
        String launch = cell.text("LDate");
        String separation = cell.text("SDate");
        String decay = cell.text("DDate");
        String parent = cell.text("Parent");
        return new GcatObject(
                jcat,
                satcat != null && satcat.chars().allMatch(Character::isDigit) ? Integer.valueOf(satcat) : null,
                cell.text("Launch_Tag"),
                cell.text("Piece"),
                cell.text("Type"),
                cell.text("Name"),
                cell.text("PLName"),
                launch, cell.date(launch),
                parent == null ? null : parent.split("[\\s*]", 2)[0],
                parent,
                separation, cell.date(separation),
                cell.text("Primary"),
                decay, cell.date(decay),
                cell.text("Status"),
                cell.text("Owner"),
                cell.text("State"),
                cell.number("Mass"),
                cell.number("Perigee"),
                cell.number("Apogee"),
                cell.number("Inc"),
                cell.text("OpOrbit"),
                cell.text("AltNames"));
    }
}
