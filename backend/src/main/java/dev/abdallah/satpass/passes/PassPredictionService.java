package dev.abdallah.satpass.passes;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import dev.abdallah.satpass.domain.SubSatellitePoint;
import dev.abdallah.satpass.domain.TrackPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hipparchus.util.FastMath;
import org.orekit.bodies.CelestialBody;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.data.DataContext;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.EclipseDetector;
import org.orekit.propagation.events.ElevationDetector;
import org.orekit.propagation.events.ElevationExtremumDetector;
import org.orekit.propagation.events.EventsLogger;
import org.orekit.propagation.events.handlers.ContinueOnEvent;
import org.orekit.propagation.sampling.OrekitStepHandler;
import org.orekit.propagation.sampling.OrekitStepInterpolator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.TrackingCoordinates;
import org.springframework.stereotype.Service;

/**
 * Computes the passes of a satellite over an observer, from a TLE.
 *
 * <h2>Chain of frames</h2>
 * SGP4 returns the satellite's position in TEME (<i>True Equator, Mean Equinox</i>), a
 * quasi-inertial frame specific to the model, not a standard IERS frame. The observer, on
 * the other hand, is fixed in ITRF, which rotates with the Earth's crust. Orekit performs
 * the TEME to ITRF conversion at each date using the Earth orientation parameters loaded
 * from {@code orekit-data}; that is why the application refuses to start without them.
 *
 * <h2>Two computation passes</h2>
 * <ol>
 *   <li>One propagation over the whole window, with event detectors, giving the
 *       <em>boundaries</em> of each pass: AOS, culmination, LOS. Those three dates come
 *       from root finding, not from sampling — which is what makes them accurate to the
 *       millisecond.</li>
 *   <li>One propagation per pass, over [AOS, LOS], filling the polyline. An
 *       {@link OrekitStepHandler} takes samples <em>inside</em> the integration steps,
 *       without restarting a propagation per point.</li>
 * </ol>
 *
 * <h2>Accepted limitations</h2>
 * <ul>
 *   <li>No atmospheric refraction correction: below 5 degrees of elevation, the
 *       atmosphere lifts the satellite's apparent image by about 0.1 degree. Under that
 *       threshold, the elevation returned here is geometric, not apparent.</li>
 *   <li>Events are searched with a step of {@value #MAX_CHECK_SECONDS} s: a pass shorter
 *       than that may escape detection. In low Earth orbit, only grazing passes — a few
 *       tenths of a degree above the threshold — are affected.</li>
 *   <li>A pass already under way when the window opens, or still under way when it
 *       closes, is discarded: its AOS or LOS falls outside the computed interval, and
 *       returning a truncated duration would be a silent lie.</li>
 * </ul>
 */
@Service
public class PassPredictionService {

    /**
     * Maximum step between two evaluations of the detection function, in seconds.
     *
     * <p>Orekit looks for sign changes on a grid of this step, then refines by root
     * finding. Orekit's default (600 s) is tuned for high orbits: in low Earth orbit, a
     * whole pass lasts 5 to 10 minutes and would regularly be skipped. 60 s leaves
     * several sampling points per pass.
     */
    private static final double MAX_CHECK_SECONDS = 60.0;

    /** Accuracy of the root search on the date of an event, in seconds. */
    private static final double THRESHOLD_SECONDS = 1.0e-3;

    /**
     * Track sampling step, in seconds.
     *
     * <p>A <em>fixed step</em>, not a fixed number of points per pass. Three reasons: the
     * instants fall on round multiples from AOS, so they read as time labels on the sky
     * chart as they are; the density of points says something true — a long pass has more
     * points because it lasts longer; and the rule fits in one sentence, which a fixed
     * point count does not ("60 points" forces you to explain why 60).
     *
     * <p>Accepted trade-off: a grazing 50 s pass only gets 4 interior points and its
     * curve is visibly angular. That is the case with the least to see, and the lower
     * bound stays correct — AOS, culmination and LOS are always present, whatever the
     * duration.
     */
    private static final double TRACK_STEP_SECONDS = 10.0;

    /**
     * Longest search window the service accepts, in hours.
     *
     * <p>Beyond ten days the SGP4 error dwarfs the accuracy on display, and the response
     * grows by one pass every ninety minutes or so, each carrying some forty points. The
     * bound lives here rather than only in the controller: the service carries the
     * invariant, and it is callable from elsewhere — a scheduled job, a test, tomorrow's
     * second entry point. The HTTP annotation turns the same rule into a 400 before a
     * propagation ever starts, which is a convenience, not the rule itself.
     */
    public static final int MAX_WINDOW_HOURS = 240;

    private static final Duration MAX_WINDOW = Duration.ofHours(MAX_WINDOW_HOURS);

    private final DataContext dataContext;
    private final OneAxisEllipsoid earth;
    private final CelestialBody sun;
    private final EclipseDetector eclipse;

    public PassPredictionService(DataContext dataContext) {
        this.dataContext = dataContext;
        // simpleEOP = false: we want the full IERS corrections, not a degraded model.
        this.earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                dataContext.getFrames().getITRF(IERSConventions.IERS_2010, false));
        this.sun = dataContext.getCelestialBodies().getSun();
        // Conservative: partial eclipse is not classified as fully illuminated.
        this.eclipse = new EclipseDetector(sun, Constants.SUN_RADIUS, earth).withPenumbra();
    }

    /**
     * @param tle             orbital elements of the satellite (never reinterpreted
     *                        outside SGP4)
     * @param observer        position of the ground observer
     * @param windowStart     start of the search window
     * @param window          duration of the window
     * @param minElevationDeg minimum elevation, in degrees, above which the satellite is
     *                        considered visible
     * @return the complete passes contained in the window, sorted by increasing AOS
     */
    public List<SatellitePass> predictPasses(TLE tle,
                                             ObserverLocation observer,
                                             Instant windowStart,
                                             Duration window,
                                             double minElevationDeg) {

        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("the search window must be positive: " + window);
        }
        if (window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "the search window exceeds " + MAX_WINDOW_HOURS + " h: " + window);
        }
        if (minElevationDeg < 0.0 || minElevationDeg >= 90.0) {
            throw new IllegalArgumentException("minimum elevation outside [0, 90): " + minElevationDeg);
        }

        TimeScale utc = dataContext.getTimeScales().getUTC();
        AbsoluteDate start = new AbsoluteDate(windowStart, utc);
        AbsoluteDate end = start.shiftedBy((double) window.toNanos() / 1.0e9);

        TopocentricFrame site = topocentricFrameFor(observer);
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        // Two detectors, two logs. The first gives the boundaries of the pass, the second
        // its culmination — which Orekit finds by zeroing the derivative of the
        // elevation, more accurate and more honest than sampling.
        EventsLogger horizonCrossings = new EventsLogger();
        EventsLogger elevationExtrema = new EventsLogger();

        ElevationDetector visibility = new ElevationDetector(site)
                .withConstantElevation(FastMath.toRadians(minElevationDeg))
                .withMaxCheck(MAX_CHECK_SECONDS)
                .withThreshold(THRESHOLD_SECONDS)
                // Without this, the default handler stops the propagation at the first
                // detected set and we would only ever see one pass.
                .withHandler(new ContinueOnEvent());

        ElevationExtremumDetector extrema = new ElevationExtremumDetector(site)
                .withMaxCheck(MAX_CHECK_SECONDS)
                .withThreshold(THRESHOLD_SECONDS)
                .withHandler(new ContinueOnEvent());

        propagator.addEventDetector(horizonCrossings.monitorDetector(visibility));
        propagator.addEventDetector(elevationExtrema.monitorDetector(extrema));
        propagator.propagate(start, end);

        List<PassBoundaries> boundaries =
                assemblePasses(horizonCrossings.getLoggedEvents(), elevationExtrema.getLoggedEvents(), site);

        List<SatellitePass> passes = new ArrayList<>(boundaries.size());
        for (PassBoundaries pass : boundaries) {
            passes.add(buildPass(tle, pass, site));
        }
        return List.copyOf(passes);
    }

    private TopocentricFrame topocentricFrameFor(ObserverLocation observer) {
        GeodeticPoint point = new GeodeticPoint(
                FastMath.toRadians(observer.latitudeDeg()),
                FastMath.toRadians(observer.longitudeDeg()),
                observer.altitudeMeters());
        return new TopocentricFrame(earth, point, "observer");
    }

    /** The three remarkable states of a pass, before formatting. */
    private record PassBoundaries(SpacecraftState aos, SpacecraftState apex, SpacecraftState los) {
    }

    /**
     * Pairs rises with sets into passes, and attaches its culmination to each one.
     *
     * <p>The events of an {@code EventsLogger} already come in the chronological order of
     * the propagation; we do not lean on that implicit guarantee and sort them.
     */
    private List<PassBoundaries> assemblePasses(List<EventsLogger.LoggedEvent> crossings,
                                                List<EventsLogger.LoggedEvent> extrema,
                                                TopocentricFrame site) {

        List<EventsLogger.LoggedEvent> ordered = new ArrayList<>(crossings);
        ordered.sort(Comparator.comparing(EventsLogger.LoggedEvent::getDate));

        List<PassBoundaries> passes = new ArrayList<>();
        SpacecraftState aosState = null;

        for (EventsLogger.LoggedEvent event : ordered) {
            if (event.isIncreasing()) {
                // A rise where a set was expected: impossible in practice, but we restart
                // from the most recent one rather than stack an inconsistent state.
                aosState = event.getState();
            } else if (aosState != null) {
                SpacecraftState losState = event.getState();
                SpacecraftState apex = findApex(aosState.getDate(), losState.getDate(), extrema, site);
                passes.add(new PassBoundaries(aosState, apex, losState));
                aosState = null;
            }
            // A set without a rise = a pass that started before the window: ignored.
        }
        // A rise without a set = a pass still under way at the end of the window: ignored.

        return passes;
    }

    /**
     * Builds the three remarkable points once, and hands those very instances both to the
     * track and to the pass.
     *
     * <p>Not a detail: {@link SatellitePass} requires its culmination to be a point of the
     * track. Sharing the instance makes that invariant hold by identity — no search, no
     * date comparison, nothing to get wrong the day the sampling changes.
     */
    private SatellitePass buildPass(TLE tle, PassBoundaries pass, TopocentricFrame site) {
        TrackPoint aos = pointAt(pass.aos(), site);
        TrackPoint culmination = pointAt(pass.apex(), site);
        TrackPoint los = pointAt(pass.los(), site);

        return new SatellitePass(aos, culmination, los,
                sampleTrack(tle, pass, site, aos, culmination, los));
    }

    /**
     * Samples the track between AOS and LOS.
     *
     * <p>The three remarkable points are not interpolated: {@link #buildPass} builds them
     * from the {@code SpacecraftState} objects the root search has already produced, and
     * passes them in. That is both more accurate and safer — the dates of the first
     * point, the culmination and the last point are then, to the bit, those of the pass
     * itself, and the invariant checked by {@link SatellitePass} holds by construction.
     *
     * <p>In between, a single {@code propagate(AOS, LOS)}: the step handler takes states
     * from <em>within</em> each integration step by interpolation. One {@code propagate()}
     * per point would be a full restart of SGP4 for every sample.
     */
    private List<TrackPoint> sampleTrack(TLE tle,
                                         PassBoundaries pass,
                                         TopocentricFrame site,
                                         TrackPoint aosPoint,
                                         TrackPoint culmination,
                                         TrackPoint losPoint) {
        AbsoluteDate aos = pass.aos().getDate();
        AbsoluteDate los = pass.los().getDate();
        AbsoluteDate apex = pass.apex().getDate();

        List<AbsoluteDate> interior = interiorSampleDates(aos, los, apex);

        List<TrackPoint> track = new ArrayList<>(interior.size() + 3);
        track.add(aosPoint);
        track.add(culmination);
        track.add(losPoint);

        if (!interior.isEmpty()) {
            TrackSampler sampler = new TrackSampler(interior, site);
            TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);
            propagator.setStepHandler(sampler);
            propagator.propagate(aos, los);
            track.addAll(sampler.sampled());
        }

        track.sort(Comparator.comparing(TrackPoint::instant));
        return track;
    }

    /**
     * The dates of the regular grid, boundaries and culmination excluded.
     *
     * <p>Offsets are recomputed as {@code i * step} rather than accumulated: the sum of
     * forty additions would drift the day the step becomes a request parameter and takes
     * a value that binary floating point cannot represent exactly.
     *
     * <p>A grid point falling within {@value #THRESHOLD_SECONDS} s of one of the three
     * remarkable dates is dropped: it would duplicate a point we already know more
     * precisely, and two near-coincident points in an SVG polyline produce joint
     * artefacts.
     */
    private List<AbsoluteDate> interiorSampleDates(AbsoluteDate aos, AbsoluteDate los, AbsoluteDate apex) {
        double duration = los.durationFrom(aos);
        double apexOffset = apex.durationFrom(aos);

        List<AbsoluteDate> dates = new ArrayList<>();
        for (int i = 1; i * TRACK_STEP_SECONDS < duration - THRESHOLD_SECONDS; i++) {
            double offset = i * TRACK_STEP_SECONDS;
            if (FastMath.abs(offset - apexOffset) <= THRESHOLD_SECONDS) {
                continue;
            }
            dates.add(aos.shiftedBy(offset));
        }
        return dates;
    }

    /**
     * Takes states at imposed dates, over the course of a single propagation.
     *
     * <p>The dates must be increasing and contained in the propagated interval; they are
     * by construction here. Each integration step consumes every date it covers.
     */
    private final class TrackSampler implements OrekitStepHandler {

        private final List<AbsoluteDate> dates;
        private final TopocentricFrame site;
        private final List<TrackPoint> points;
        private int next;

        private TrackSampler(List<AbsoluteDate> dates, TopocentricFrame site) {
            this.dates = dates;
            this.site = site;
            this.points = new ArrayList<>(dates.size());
        }

        @Override
        public void handleStep(OrekitStepInterpolator interpolator) {
            AbsoluteDate stepEnd = interpolator.getCurrentState().getDate();
            while (next < dates.size() && dates.get(next).compareTo(stepEnd) <= 0) {
                points.add(pointAt(interpolator.getInterpolatedState(dates.get(next)), site));
                next++;
            }
        }

        @Override
        public void finish(SpacecraftState finalState) {
            // The last step ends at LOS and has therefore normally consumed everything.
            // A remainder would signal a date outside the propagated interval, that is, a
            // flaw in how the grid was built: we fail rather than return a silently
            // truncated curve.
            if (next < dates.size()) {
                throw new IllegalStateException(
                        (dates.size() - next) + " sampling date(s) outside the propagated interval, "
                                + "starting at " + dates.get(next));
            }
        }

        private List<TrackPoint> sampled() {
            return points;
        }
    }

    /**
     * Finds the culmination of the pass: the elevation extremum between AOS and LOS whose
     * derivative goes from positive to negative (a maximum, hence a decreasing event).
     *
     * <p>Elevation is continuous, equal to the threshold at both boundaries and strictly
     * above it in between: Rolle's theorem guarantees such a maximum exists. Failing to
     * find one signals a detection step that is too coarse, not an exotic orbit — hence
     * the outright failure rather than a plausible but wrong fallback value.
     *
     * <p>Rolle guarantees existence, not uniqueness, and the logged events are not sorted
     * by us: we therefore take the <em>highest</em> extremum in the interval, not the
     * first one the list happens to hold. With a single maximum — the normal case — the
     * two are the same; the day a pass has two, the one that matters is the higher one.
     */
    private SpacecraftState findApex(AbsoluteDate aos,
                                     AbsoluteDate los,
                                     List<EventsLogger.LoggedEvent> extrema,
                                     TopocentricFrame site) {
        return extrema.stream()
                .filter(event -> !event.isIncreasing())
                .filter(event -> event.getDate().compareTo(aos) >= 0 && event.getDate().compareTo(los) <= 0)
                .map(EventsLogger.LoggedEvent::getState)
                .max(Comparator.comparingDouble(state -> trackingCoordinates(state, site).getElevation()))
                .orElseThrow(() -> new IllegalStateException(
                        "no elevation maximum found between " + aos + " and " + los
                                + " — the detection step (" + MAX_CHECK_SECONDS + " s) is too coarse"));
    }

    /**
     * Describes a propagated state in its two forms: seen from the ground and seen from
     * space.
     *
     * <p>{@code subPoint} comes from projecting the position onto the ellipsoid in ITRF,
     * on the Orekit side. The browser never recomputes it: that is the condition for the
     * globe to stay a display and not become a second propagator.
     */
    private TrackPoint pointAt(SpacecraftState state, TopocentricFrame site) {
        TrackingCoordinates seen = trackingCoordinates(state, site);
        GeodeticPoint sub = earth.transform(state.getPosition(), state.getFrame(), state.getDate());

        boolean illuminated = eclipse.g(state) > 0.0;
        double sunElevation = site.getTrackingCoordinates(
                sun.getPosition(state.getDate(), state.getFrame()),
                state.getFrame(), state.getDate()).getElevation();
        // Civil twilight is a reproducible geometric criterion, not a brightness model.
        boolean visible = illuminated && sunElevation <= FastMath.toRadians(-6.0);

        return new TrackPoint(
                toInstant(state.getDate()),
                degreesInCircle(seen.getAzimuth()),
                FastMath.toDegrees(seen.getElevation()),
                seen.getRange() / 1000.0,
                new SubSatellitePoint(
                        FastMath.toDegrees(sub.getLatitude()),
                        FastMath.toDegrees(sub.getLongitude()),
                        sub.getAltitude() / 1000.0),
                illuminated, visible);
    }

    private TrackingCoordinates trackingCoordinates(SpacecraftState state, TopocentricFrame site) {
        return site.getTrackingCoordinates(state.getPosition(), state.getFrame(), state.getDate());
    }

    private Instant toInstant(AbsoluteDate date) {
        return date.toInstant(dataContext.getTimeScales());
    }

    /**
     * Converts an azimuth to degrees in [0, 360).
     *
     * <p>Orekit already normalises the azimuth into [0, 2pi): the modulo therefore does
     * not fix a different convention, it rules out the one remaining case, an azimuth
     * just under 2pi which, converted to degrees, would round to exactly 360.
     */
    private static double degreesInCircle(double radians) {
        double degrees = FastMath.toDegrees(radians) % 360.0;
        return degrees < 0.0 ? degrees + 360.0 : degrees;
    }
}
