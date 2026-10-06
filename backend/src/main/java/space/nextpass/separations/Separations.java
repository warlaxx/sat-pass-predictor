package space.nextpass.separations;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * The shapes the separation pages read, built from the GCAT tables by
 * {@link SeparationRepository}. An event is one parent releasing one or more objects at
 * the same recorded moment: sixty-two fragments of one breakup are one event, not
 * sixty-two.
 */
public final class Separations {

    private Separations() {}

    /** Releases leave at least one object that is not debris; fragmentations leave only debris. */
    public enum Kind { RELEASE, FRAGMENTATION }

    /**
     * A date with the precision it was recorded with. {@code text} is the record itself
     * ({@code 2026 Sep?}); {@code at} is the start of the interval it denotes, to sort by,
     * never to be shown without {@code precision}.
     */
    public record Date(String text, Instant at, String precision, boolean uncertain) {}

    /** An orbit as recorded today. {@code apogeeKm} is null for an object that left Earth orbit. */
    public record Orbit(Double perigeeKm, Double apogeeKm, Double inclinationDeg, String orbitClass) {}

    /**
     * One catalogued object.
     *
     * @param id the stable identifier of the record ({@code S100685})
     * @param noradId the NORAD catalogue number, null when there is none
     * @param role payload, rocket stage, component or debris, from the record's type
     * @param inOrbit false once the record says it re-entered, landed or was deorbited
     * @param image a free photograph of the object, left out of the JSON when there is none
     */
    public record SpaceObject(
            String id,
            Integer noradId,
            String name,
            String payloadName,
            String piece,
            String role,
            String owner,
            String state,
            Double massKg,
            Date launch,
            Orbit orbit,
            boolean inOrbit,
            Evidence evidence,
            @JsonInclude(JsonInclude.Include.NON_NULL) Image image) {}

    /**
     * A photograph from Wikimedia Commons (ABD-45) and the credit its licence requires:
     * the page shows {@code author}, {@code licence} (linked to {@code licenceUrl} when
     * there is one) and a link to {@code sourceUrl}, the file's page on Commons.
     *
     * @param url a thumbnail on upload.wikimedia.org, 500 pixels wide
     */
    public record Image(String url, Integer width, Integer height, String author, String licence,
                        String licenceUrl, String sourceUrl) {}

    /** The fields of the record an event page quotes as evidence, verbatim. */
    public record Evidence(String id, Integer satcat, String piece, String name, String payloadName,
                         String parent, String separationDate, String owner, String status) {}

    /** One line of the list. {@code id} is the event's address: its first child's record. */
    public record Summary(
            String id,
            Kind kind,
            Date date,
            String parentName,
            String parentOwner,
            String parentState,
            String firstChildName,
            Integer firstChildNoradId,
            int children,
            Orbit orbit,
            boolean inOrbit) {}

    /** Events per month of the current year, for the chart above the list. */
    public record Month(String month, int releases, int fragmentations) {}

    public record Stats(int objects, int objectsThisYear, int year, List<Month> byMonth) {}

    public record ListResponse(List<Summary> events, Stats stats, Instant updatedAt) {}

    /**
     * One event in full. {@code children} is capped (a breakup can leave hundreds of
     * fragments); {@code childCount} is the real number.
     */
    public record Event(
            String id,
            Kind kind,
            Date date,
            SpaceObject parent,
            SpaceObject grandparent,
            List<SpaceObject> children,
            int childCount,
            Instant updatedAt) {}
}
