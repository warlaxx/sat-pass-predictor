package space.nextpass.gcat;

import java.time.Duration;
import java.time.Instant;
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
     * How long the launch lasts for a stage or anything fixed to it. A stage delivering a
     * satellite to a high orbit releases it hours after lift-off, often past midnight UTC;
     * on real GCAT rows, the releases of launch hardware cluster under one day and thin out
     * after two (ABD-12).
     */
    static final Duration LAUNCH_PHASE = Duration.ofDays(2);

    /**
     * What the rule needs of the parent's own row: its GCAT type and its own parent's
     * identifier. A {@code null} parent stands for one outside the satellite catalogue.
     */
    public record Parent(String type, String parent) {

        /**
         * Launch hardware by its type, or a vehicle GCAT counts as a payload but hangs on an
         * entry of its launch vehicle database ({@code R…}): Starship, whose parent is the
         * Super Heavy booster, delivers its payloads as a stage does.
         */
        boolean isLaunchVehicle() {
            return isLaunchHardware(type) || (parent != null && parent.startsWith("R"));
        }
    }

    /**
     * Whether this object separated in orbit from its parent, rather than being launched.
     * The answer depends on what the parent is, which only the parent's own row says.
     * See {@link #isSeparation(String, String, GcatDate, GcatDate, Parent)}.
     */
    public boolean isSeparation(Parent of) {
        return isSeparation(type, parent, launch, separation, of);
    }

    /**
     * The definition of a separation, refined on real cases (ABD-12; ROADMAP.md lists them).
     *
     * <ul>
     *   <li>No parent, no separation date, a parent outside the satellite catalogues
     *       (a launch vehicle's {@code R…}), or a row GCAT marks spurious ({@code Z}),
     *       deleted ({@code X}) or an alias of another: never.</li>
     *   <li>The parent belongs to the launch vehicle ({@link Parent#isLaunchVehicle()}) - a
     *       stage, an adapter, a fairing, a motor or tank, an ejection mechanism, a carrier
     *       permanently attached to the stage: only {@link #LAUNCH_PHASE} or more after the
     *       launch. Before, it is the launch delivering its payloads; after, a stage breaking
     *       up or releasing late.</li>
     *   <li>The parent is not catalogued: the same two days, since nothing says what it
     *       is. It is often a deployer inside a station or a tug; it is sometimes the stage.</li>
     *   <li>The parent is a spacecraft and the object a payload: always, launch day
     *       included. That is a deployer emptying - a tug's cubesats, a host's subsatellites.</li>
     *   <li>The parent is a spacecraft and the object anything else - a component, debris:
     *       after the launch day. On the launch day it is deployment hardware, a cover or a
     *       clamp; a breakup that soon (Kosmos-249, 1968) is the rare case this misses.</li>
     * </ul>
     *
     * A date is the start of the interval GCAT gives: {@code 2026 May?} starts on 1 May, so
     * a vague date only counts when even its earliest reading is late enough.
     */
    static boolean isSeparation(String type, String parent, GcatDate launch, GcatDate separation, Parent of) {
        if (parent == null || separation == null || !(parent.startsWith("S") || parent.startsWith("A"))) {
            return false;
        }
        char kind = flag(type, 0);
        if (kind == 'Z' || kind == 'X' || flag(type, 1) == 'A') {
            return false;
        }
        if (launch == null) {
            return true;
        }
        Instant released = separation.start();
        if (of == null || !parent.startsWith("S") || of.isLaunchVehicle()) {
            return !released.isBefore(launch.start().plus(LAUNCH_PHASE));
        }
        if (kind == 'P') {
            return true;
        }
        return !released.isBefore(launch.start().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS));
    }

    /**
     * A GCAT type that belongs to the launch vehicle: a stage ({@code R}); a component that
     * is an adapter, a fairing, a motor or tank, or an ejection mechanism (fourth character
     * {@code A}, {@code F}, {@code M}, {@code V}); or anything permanently attached to the
     * stage (third character {@code A}), such as a rideshare carrier.
     * See https://planet4589.org/space/gcat/web/intro/type.html.
     */
    static boolean isLaunchHardware(String type) {
        return flag(type, 0) == 'R'
                || flag(type, 2) == 'A'
                || (flag(type, 0) == 'C' && "AFMV".indexOf(flag(type, 3)) >= 0);
    }

    /** One character of a GCAT type, a space past its end: GCAT pads the column. */
    private static char flag(String type, int index) {
        return type != null && index < type.length() ? type.charAt(index) : ' ';
    }
}
