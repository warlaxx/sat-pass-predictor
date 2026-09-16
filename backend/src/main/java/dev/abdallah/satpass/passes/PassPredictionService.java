package dev.abdallah.satpass.passes;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.domain.SatellitePass;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hipparchus.util.FastMath;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.data.DataContext;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.ElevationDetector;
import org.orekit.propagation.events.ElevationExtremumDetector;
import org.orekit.propagation.events.EventsLogger;
import org.orekit.propagation.events.handlers.ContinueOnEvent;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.TrackingCoordinates;
import org.springframework.stereotype.Service;

/**
 * Calcule les passages d'un satellite au-dessus d'un observateur, a partir d'un TLE.
 *
 * <h2>Chaine de reperes</h2>
 * SGP4 restitue la position du satellite dans TEME (<i>True Equator, Mean Equinox</i>),
 * un repere quasi inertiel propre au modele, pas un repere standard de l'IERS.
 * L'observateur, lui, est fixe dans ITRF, qui tourne avec la croute terrestre.
 * Orekit fait la conversion TEME -> ITRF a chaque date en s'appuyant sur les parametres
 * d'orientation terrestre charges depuis {@code orekit-data} ; c'est pour cela que
 * l'application refuse de demarrer sans eux.
 *
 * <h2>Limites assumees</h2>
 * <ul>
 *   <li>Aucune correction de refraction atmospherique : sous 5 degres d'elevation,
 *       l'atmosphere releve l'image du satellite d'environ 0,1 degre. En deca de ce
 *       seuil, l'elevation renvoyee ici est geometrique, pas apparente.</li>
 *   <li>Les evenements sont cherches par pas de {@value #MAX_CHECK_SECONDS} s : un
 *       passage plus court que cette duree peut echapper a la detection. En orbite
 *       basse, seuls des passages rasants — quelques dixiemes de degre au-dessus du
 *       seuil — sont concernes.</li>
 *   <li>Un passage deja commence a l'ouverture de la fenetre, ou encore en cours a sa
 *       fermeture, est ecarte : ses AOS ou LOS sont hors de l'intervalle calcule, et
 *       renvoyer une duree tronquee serait un mensonge silencieux.</li>
 * </ul>
 */
@Service
public class PassPredictionService {

    /**
     * Pas maximal entre deux evaluations de la fonction de detection, en secondes.
     *
     * <p>Orekit cherche les changements de signe sur une grille de ce pas, puis affine
     * par recherche de racine. Le defaut d'Orekit (600 s) est cale sur des orbites
     * hautes : en orbite basse, un passage entier dure 5 a 10 minutes et serait
     * regulierement saute. 60 s laisse plusieurs points d'echantillonnage par passage.
     */
    private static final double MAX_CHECK_SECONDS = 60.0;

    /** Precision de la recherche de racine sur la date d'un evenement, en secondes. */
    private static final double THRESHOLD_SECONDS = 1.0e-3;

    private final DataContext dataContext;
    private final OneAxisEllipsoid earth;

    public PassPredictionService(DataContext dataContext) {
        this.dataContext = dataContext;
        // simpleEOP = false : on veut les corrections IERS completes, pas un modele degrade.
        this.earth = new OneAxisEllipsoid(
                Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING,
                dataContext.getFrames().getITRF(IERSConventions.IERS_2010, false));
    }

    /**
     * @param tle             elements orbitaux du satellite (jamais reinterpretes hors de SGP4)
     * @param observer        position de l'observateur au sol
     * @param windowStart     debut de la fenetre de recherche
     * @param window          duree de la fenetre
     * @param minElevationDeg elevation minimale, en degres, au-dessus de laquelle on
     *                        considere le satellite visible
     * @return les passages complets contenus dans la fenetre, tries par AOS croissant
     */
    public List<SatellitePass> predictPasses(TLE tle,
                                             ObserverLocation observer,
                                             Instant windowStart,
                                             Duration window,
                                             double minElevationDeg) {

        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("la fenetre de recherche doit etre positive : " + window);
        }
        if (minElevationDeg < 0.0 || minElevationDeg >= 90.0) {
            throw new IllegalArgumentException("elevation minimale hors de [0, 90) : " + minElevationDeg);
        }

        TimeScale utc = dataContext.getTimeScales().getUTC();
        AbsoluteDate start = new AbsoluteDate(windowStart, utc);
        AbsoluteDate end = start.shiftedBy((double) window.toNanos() / 1.0e9);

        TopocentricFrame site = topocentricFrameFor(observer);
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        // Deux detecteurs, deux journaux. Le premier donne les bornes du passage,
        // le second le sommet — qu'Orekit trouve par annulation de la derivee de
        // l'elevation, ce qui est plus precis et plus honnete qu'un echantillonnage.
        EventsLogger horizonCrossings = new EventsLogger();
        EventsLogger elevationExtrema = new EventsLogger();

        ElevationDetector visibility = new ElevationDetector(site)
                .withConstantElevation(FastMath.toRadians(minElevationDeg))
                .withMaxCheck(MAX_CHECK_SECONDS)
                .withThreshold(THRESHOLD_SECONDS)
                // Sans ceci, le gestionnaire par defaut arrete la propagation au premier
                // coucher detecte et on ne verrait qu'un seul passage.
                .withHandler(new ContinueOnEvent());

        ElevationExtremumDetector extrema = new ElevationExtremumDetector(site)
                .withMaxCheck(MAX_CHECK_SECONDS)
                .withThreshold(THRESHOLD_SECONDS)
                .withHandler(new ContinueOnEvent());

        propagator.addEventDetector(horizonCrossings.monitorDetector(visibility));
        propagator.addEventDetector(elevationExtrema.monitorDetector(extrema));
        propagator.propagate(start, end);

        return assemblePasses(horizonCrossings.getLoggedEvents(), elevationExtrema.getLoggedEvents(), site);
    }

    private TopocentricFrame topocentricFrameFor(ObserverLocation observer) {
        GeodeticPoint point = new GeodeticPoint(
                FastMath.toRadians(observer.latitudeDeg()),
                FastMath.toRadians(observer.longitudeDeg()),
                observer.altitudeMeters());
        return new TopocentricFrame(earth, point, "observer");
    }

    /**
     * Apparie les levers et les couchers en passages, et rattache a chacun son sommet.
     *
     * <p>Les evenements d'un {@code EventsLogger} sont deja dans l'ordre chronologique
     * de la propagation ; on ne s'appuie pas sur cette garantie implicite et on trie.
     */
    private List<SatellitePass> assemblePasses(List<EventsLogger.LoggedEvent> crossings,
                                               List<EventsLogger.LoggedEvent> extrema,
                                               TopocentricFrame site) {

        List<EventsLogger.LoggedEvent> ordered = new ArrayList<>(crossings);
        ordered.sort(Comparator.comparing(EventsLogger.LoggedEvent::getDate));

        List<SatellitePass> passes = new ArrayList<>();
        SpacecraftState aosState = null;

        for (EventsLogger.LoggedEvent event : ordered) {
            if (event.isIncreasing()) {
                // Un lever alors qu'on en attendait un coucher : impossible en pratique,
                // mais on repart du plus recent plutot que d'empiler un etat incoherent.
                aosState = event.getState();
            } else if (aosState != null) {
                passes.add(buildPass(aosState, event.getState(), extrema, site));
                aosState = null;
            }
            // Un coucher sans lever = passage commence avant la fenetre : ignore.
        }
        // Un lever sans coucher = passage encore en cours a la fin de la fenetre : ignore.

        return List.copyOf(passes);
    }

    private SatellitePass buildPass(SpacecraftState aosState,
                                    SpacecraftState losState,
                                    List<EventsLogger.LoggedEvent> extrema,
                                    TopocentricFrame site) {

        TrackingCoordinates aos = track(aosState, site);
        TrackingCoordinates los = track(losState, site);
        SpacecraftState apex = findApex(aosState.getDate(), losState.getDate(), extrema);
        TrackingCoordinates max = track(apex, site);

        return new SatellitePass(
                toInstant(aosState.getDate()),
                degreesInCircle(aos.getAzimuth()),
                toInstant(apex.getDate()),
                FastMath.toDegrees(max.getElevation()),
                degreesInCircle(max.getAzimuth()),
                toInstant(losState.getDate()),
                degreesInCircle(los.getAzimuth()));
    }

    /**
     * Trouve le sommet du passage : l'extremum d'elevation situe entre AOS et LOS dont
     * la derivee passe du positif au negatif (un maximum, donc un evenement decroissant).
     *
     * <p>L'elevation est continue, egale au seuil aux deux bornes et strictement
     * au-dessus entre les deux : le theoreme de Rolle garantit l'existence de ce maximum.
     * Ne pas le trouver signale un pas de detection trop grossier, pas une orbite exotique
     * — d'ou l'echec franc plutot qu'une valeur de repli plausible mais fausse.
     */
    private SpacecraftState findApex(AbsoluteDate aos,
                                     AbsoluteDate los,
                                     List<EventsLogger.LoggedEvent> extrema) {
        return extrema.stream()
                .filter(event -> !event.isIncreasing())
                .filter(event -> event.getDate().compareTo(aos) >= 0 && event.getDate().compareTo(los) <= 0)
                .map(EventsLogger.LoggedEvent::getState)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "aucun maximum d'elevation trouve entre " + aos + " et " + los
                                + " — le pas de detection (" + MAX_CHECK_SECONDS + " s) est trop grossier"));
    }

    private TrackingCoordinates track(SpacecraftState state, TopocentricFrame site) {
        return site.getTrackingCoordinates(state.getPosition(), state.getFrame(), state.getDate());
    }

    private Instant toInstant(AbsoluteDate date) {
        return date.toInstant(dataContext.getTimeScales());
    }

    /**
     * Convertit un azimut en degres dans [0, 360).
     *
     * <p>Orekit normalise deja l'azimut dans [0, 2pi) : le modulo ne redresse donc pas
     * une convention differente, il ecarte le seul cas restant, un azimut juste sous
     * 2pi qui, converti en degres, arrondirait exactement a 360.
     */
    private static double degreesInCircle(double radians) {
        double degrees = FastMath.toDegrees(radians) % 360.0;
        return degrees < 0.0 ? degrees + 360.0 : degrees;
    }
}
