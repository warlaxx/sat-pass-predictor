package space.nextpass.gcat;

import java.time.temporal.ChronoUnit;

/**
 * One row of a GCAT satellite catalogue, reduced to the columns NextPass uses.
 *
 * <p>Dates keep GCAT's own text next to the parsed value: the text is what an event page
 * quotes as evidence, the parsed value is what it sorts by. A parsed value is
 * {@code null} when the text is GCAT's "none" or a shape {@link GcatDate} does not know;
 * the text survives either way.
 *
 * @param jcat GCAT's own identifier ({@code S00001}), the key: every object has one,
 *     unlike a NORAD number
 * @param satcat the NORAD catalogue number, {@code null} when GCAT has none ({@code NNA})
 * @param parent the GCAT identifier of the object this one came from, {@code null} if none
 * @param parentText GCAT's whole cell, kept verbatim: an "extended JCAT identifier" may add a
 *     port location after one or more spaces ({@code S16273  AL}: where on the parent the
 *     object was attached, with no table of codes), and an {@code *} after the identifier
 *     flags a launch designation that may not be the parent's launch
 *     (https://planet4589.org/space/gcat/web/intro/jcat.html). The event page reads it in
 *     {@code separation-format.ts}, {@code parentCell}
 */
public record GcatObject(
        String jcat,
        Integer satcat,
        String launchTag,
        String piece,
        String type,
        String name,
        String payloadName,
        String launchText,
        GcatDate launch,
        String parent,
        String parentText,
        String separationText,
        GcatDate separation,
        String primaryBody,
        String decayText,
        GcatDate decay,
        String status,
        String owner,
        String state,
        Double massKg,
        Double perigeeKm,
        Double apogeeKm,
        Double inclinationDeg,
        String opOrbit,
        String altNames) {

    /**
     * Working definition from the roadmap (phase 3): released by another catalogued
     * satellite ({@code S…} parent), on a day other than its launch. Most rows instead
     * point at the stage that carried them, and those are launches, not separations.
     * Phase 3.1 refines this on real cases.
     */
    public boolean isSeparation() {
        if (parent == null || !parent.startsWith("S") || separation == null) {
            return false;
        }
        return launch == null || !day(separation).equals(day(launch));
    }

    private static java.time.Instant day(GcatDate date) {
        return date.start().truncatedTo(ChronoUnit.DAYS);
    }
}
