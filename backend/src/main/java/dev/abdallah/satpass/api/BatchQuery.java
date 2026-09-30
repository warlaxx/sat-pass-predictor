package dev.abdallah.satpass.api;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.passes.PassPredictionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

/**
 * A batch request, parsed from its query string: several satellites, several sites, one
 * window and one threshold. Every satellite is predicted for every site.
 *
 * <p>The parameters are read from the raw query rather than bound by Spring MVC, and
 * that is deliberate. A site is {@code lat,lon[,alt]}; Spring splits a single
 * comma-separated value into a list, which would turn one site into three numbers. And
 * the quota interceptor must count the predictions <em>before</em> the controller runs,
 * from the same parameters and by the same rule: one parser for both is the only way the
 * number billed and the number computed cannot drift apart.
 *
 * <p>The bounds are those of {@code /v1/passes}, repeated here because this endpoint
 * cannot use the annotations. The caps on the batch itself exist for the response time.
 * Measured on an Apple M-series laptop, the worst case — {@value #MAX_PREDICTIONS} cache
 * misses over a 240 h window — took 7.5 s and returned 13.8 MB with tracks; the same
 * batch served from the prediction cache with {@code track=false} took 8 ms and 0.5 MB.
 *
 * <p>Duplicates are dropped, in order of first appearance. They would be billed twice
 * for one identical answer, and the milestone 10 comparison already treats the same
 * NORAD number given twice as one satellite.
 */
public record BatchQuery(List<Integer> noradIds,
                         List<ObserverLocation> sites,
                         int hours,
                         double minElevationDeg,
                         boolean track) {

    public static final int MAX_SATELLITES = 10;
    public static final int MAX_SITES = 10;
    public static final int MAX_PREDICTIONS = 25;

    public BatchQuery {
        noradIds = List.copyOf(noradIds);
        sites = List.copyOf(sites);
    }

    public int predictions() {
        return noradIds.size() * sites.size();
    }

    /**
     * What admission charges for this query string: its number of predictions, or one if
     * it is not a valid batch. An invalid request costs a call like any other refused
     * request on {@code /v1/passes} — it is admitted, then refused with a 400.
     */
    public static int predictionCount(Map<String, String[]> parameters) {
        try {
            return parse(parameters).predictions();
        } catch (IllegalArgumentException invalid) {
            return 1;
        }
    }

    /** @throws IllegalArgumentException with a message fit for the Problem Details body */
    public static BatchQuery parse(Map<String, String[]> parameters) {
        SequencedSet<Integer> noradIds = new LinkedHashSet<>();
        for (String value : values(parameters, "noradId")) {
            // One parameter may carry several numbers: noradId=25544,20580.
            for (String part : value.split(",", -1)) {
                noradIds.add((int) number("noradId", part, 1, 99_999, true));
            }
        }
        SequencedSet<ObserverLocation> sites = new LinkedHashSet<>();
        for (String value : values(parameters, "site")) {
            sites.add(site(value));
        }
        if (noradIds.isEmpty()) {
            throw new IllegalArgumentException("At least one noradId is required");
        }
        if (sites.isEmpty()) {
            throw new IllegalArgumentException("At least one site is required, as site=lat,lon or site=lat,lon,alt");
        }
        if (noradIds.size() > MAX_SATELLITES) {
            throw new IllegalArgumentException("At most " + MAX_SATELLITES + " distinct satellites per batch");
        }
        if (sites.size() > MAX_SITES) {
            throw new IllegalArgumentException("At most " + MAX_SITES + " distinct sites per batch");
        }
        if (noradIds.size() * sites.size() > MAX_PREDICTIONS) {
            throw new IllegalArgumentException("At most " + MAX_PREDICTIONS
                    + " predictions (satellites × sites) per batch; this one asks for "
                    + noradIds.size() * sites.size());
        }
        int hours = (int) number("hours", single(parameters, "hours", "48"),
                1, PassPredictionService.MAX_WINDOW_HOURS, true);
        double minElevation = number("minElevation", single(parameters, "minElevation", "10"), 0, 89, false);
        return new BatchQuery(new ArrayList<>(noradIds), new ArrayList<>(sites), hours, minElevation,
                bool("track", single(parameters, "track", "true")));
    }

    private static ObserverLocation site(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("Invalid site '" + value + "': expected lat,lon or lat,lon,alt");
        }
        return new ObserverLocation(
                number("site latitude", parts[0], -90, 90, false),
                number("site longitude", parts[1], -180, 180, false),
                parts.length == 3 ? number("site altitude", parts[2], -500, 9000, false) : 0);
    }

    private static List<String> values(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        return values == null ? List.of() : List.of(values);
    }

    private static String single(Map<String, String[]> parameters, String name, String fallback) {
        List<String> values = values(parameters, name);
        if (values.size() > 1) {
            throw new IllegalArgumentException("'" + name + "' is given more than once");
        }
        return values.isEmpty() ? fallback : values.getFirst();
    }

    private static double number(String name, String raw, double min, double max, boolean integer) {
        String text = raw.strip();
        double value;
        try {
            value = integer ? Integer.parseInt(text) : Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + name + " '" + raw + "'");
        }
        // parseDouble accepts NaN and Infinity; neither is a coordinate, and NaN would
        // slip through both comparisons below.
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                    "'" + name + "' must be between " + format(min) + " and " + format(max) + ", got '" + raw + "'");
        }
        return value;
    }

    private static boolean bool(String name, String raw) {
        return switch (raw.strip()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("'" + name + "' must be true or false");
        };
    }

    private static String format(double bound) {
        return bound == Math.rint(bound) ? Long.toString((long) bound) : Double.toString(bound);
    }
}
