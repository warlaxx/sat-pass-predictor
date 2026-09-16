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
import org.orekit.propagation.sampling.OrekitStepHandler;
import org.orekit.propagation.sampling.OrekitStepInterpolator;
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
 * <h2>Deux passes de calcul</h2>
 * <ol>
 *   <li>Une propagation sur toute la fenetre, avec detecteurs d'evenements, qui donne
 *       les <em>bornes</em> de chaque passage : AOS, sommet, LOS. Ces trois dates sont
 *       obtenues par recherche de racine, pas par echantillonnage — c'est ce qui les
 *       rend precises a la milliseconde.</li>
 *   <li>Une propagation par passage, sur [AOS, LOS], qui remplit la polyligne. Un
 *       {@link OrekitStepHandler} y preleve les echantillons <em>a l'interieur</em> des
 *       pas d'integration, sans relancer une propagation par point.</li>
 * </ol>
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
 *   <li>{@code illuminated} vaut {@code false} sur tous les points jusqu'au jalon 10.
 *       Le champ existe deja pour ne pas casser le contrat d'API le jour ou le calcul
 *       d'eclipse arrive.</li>
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

    /**
     * Pas d'echantillonnage de la trajectoire, en secondes.
     *
     * <p>Pas <em>fixe</em>, et non nombre de points fixe par passage. Trois raisons :
     * les instants tombent sur des multiples ronds depuis l'AOS, donc lisibles tels
     * quels comme etiquettes horaires sur la carte du ciel ; la densite de points dit
     * quelque chose de vrai — un passage long a plus de points parce qu'il dure plus
     * longtemps ; et la regle tient en une phrase, ce qu'un nombre de points fixe ne
     * fait pas ("60 points" oblige a expliquer pourquoi 60).
     *
     * <p>Contrepartie assumee : un passage rasant de 50 s ne donne que 4 points
     * intermediaires et sa courbe est visiblement anguleuse. C'est le cas ou il y a le
     * moins a voir, et la borne basse reste correcte — AOS, sommet et LOS sont toujours
     * presents, quelle que soit la duree.
     */
    private static final double TRACK_STEP_SECONDS = 10.0;

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

        List<PassBoundaries> boundaries =
                assemblePasses(horizonCrossings.getLoggedEvents(), elevationExtrema.getLoggedEvents());

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

    /** Les trois etats remarquables d'un passage, avant mise en forme. */
    private record PassBoundaries(SpacecraftState aos, SpacecraftState apex, SpacecraftState los) {
    }

    /**
     * Apparie les levers et les couchers en passages, et rattache a chacun son sommet.
     *
     * <p>Les evenements d'un {@code EventsLogger} sont deja dans l'ordre chronologique
     * de la propagation ; on ne s'appuie pas sur cette garantie implicite et on trie.
     */
    private List<PassBoundaries> assemblePasses(List<EventsLogger.LoggedEvent> crossings,
                                                List<EventsLogger.LoggedEvent> extrema) {

        List<EventsLogger.LoggedEvent> ordered = new ArrayList<>(crossings);
        ordered.sort(Comparator.comparing(EventsLogger.LoggedEvent::getDate));

        List<PassBoundaries> passes = new ArrayList<>();
        SpacecraftState aosState = null;

        for (EventsLogger.LoggedEvent event : ordered) {
            if (event.isIncreasing()) {
                // Un lever alors qu'on en attendait un coucher : impossible en pratique,
                // mais on repart du plus recent plutot que d'empiler un etat incoherent.
                aosState = event.getState();
            } else if (aosState != null) {
                SpacecraftState losState = event.getState();
                SpacecraftState apex = findApex(aosState.getDate(), losState.getDate(), extrema);
                passes.add(new PassBoundaries(aosState, apex, losState));
                aosState = null;
            }
            // Un coucher sans lever = passage commence avant la fenetre : ignore.
        }
        // Un lever sans coucher = passage encore en cours a la fin de la fenetre : ignore.

        return passes;
    }

    private SatellitePass buildPass(TLE tle, PassBoundaries pass, TopocentricFrame site) {
        TrackingCoordinates aos = trackingCoordinates(pass.aos(), site);
        TrackingCoordinates los = trackingCoordinates(pass.los(), site);
        TrackingCoordinates max = trackingCoordinates(pass.apex(), site);

        return new SatellitePass(
                toInstant(pass.aos().getDate()),
                degreesInCircle(aos.getAzimuth()),
                toInstant(pass.apex().getDate()),
                FastMath.toDegrees(max.getElevation()),
                degreesInCircle(max.getAzimuth()),
                toInstant(pass.los().getDate()),
                degreesInCircle(los.getAzimuth()),
                sampleTrack(tle, pass, site));
    }

    /**
     * Echantillonne la trajectoire entre AOS et LOS.
     *
     * <p>Les trois points remarquables ne sont pas interpoles : ils sont construits a
     * partir des {@code SpacecraftState} que la recherche de racine a deja produits.
     * C'est a la fois plus precis et plus sur — les dates du premier point, du sommet et
     * du dernier point sont alors, au bit pres, celles du passage lui-meme, et
     * l'invariant verifie par {@link SatellitePass} tient par construction.
     *
     * <p>Entre les deux, un seul {@code propagate(AOS, LOS)} : le gestionnaire de pas
     * preleve les etats <em>dans</em> chaque pas d'integration par interpolation. Un
     * {@code propagate()} par point serait une relance complete de SGP4 a chaque
     * echantillon.
     */
    private List<TrackPoint> sampleTrack(TLE tle, PassBoundaries pass, TopocentricFrame site) {
        AbsoluteDate aos = pass.aos().getDate();
        AbsoluteDate los = pass.los().getDate();
        AbsoluteDate apex = pass.apex().getDate();

        List<AbsoluteDate> interior = interiorSampleDates(aos, los, apex);

        List<TrackPoint> track = new ArrayList<>(interior.size() + 3);
        track.add(pointAt(pass.aos(), site));
        track.add(pointAt(pass.apex(), site));
        track.add(pointAt(pass.los(), site));

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
     * Les dates de la grille reguliere, bornes et sommet exclus.
     *
     * <p>Un point de grille tombant a moins de {@value #THRESHOLD_SECONDS} s d'une de ces
     * trois dates est ecarte : il ferait doublon avec un point deja plus precis, et deux
     * points quasi confondus dans une polyligne SVG produisent des artefacts de jointure.
     */
    private List<AbsoluteDate> interiorSampleDates(AbsoluteDate aos, AbsoluteDate los, AbsoluteDate apex) {
        double duration = los.durationFrom(aos);
        double apexOffset = apex.durationFrom(aos);

        List<AbsoluteDate> dates = new ArrayList<>();
        for (double offset = TRACK_STEP_SECONDS; offset < duration - THRESHOLD_SECONDS;
                offset += TRACK_STEP_SECONDS) {
            if (FastMath.abs(offset - apexOffset) <= THRESHOLD_SECONDS) {
                continue;
            }
            dates.add(aos.shiftedBy(offset));
        }
        return dates;
    }

    /**
     * Preleve des etats a des dates imposees, au fil d'une unique propagation.
     *
     * <p>Les dates doivent etre croissantes et contenues dans l'intervalle propage ;
     * c'est le cas par construction ici. Chaque pas d'integration consomme toutes les
     * dates qu'il recouvre.
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
            // Le dernier pas se termine au LOS et a donc normalement tout consomme.
            // Un reliquat signalerait une date hors de l'intervalle propage, c'est-a-dire
            // un defaut de construction de la grille : on echoue plutot que de rendre une
            // courbe silencieusement tronquee.
            if (next < dates.size()) {
                throw new IllegalStateException(
                        (dates.size() - next) + " date(s) d'echantillonnage hors de l'intervalle propage, "
                                + "a partir de " + dates.get(next));
            }
        }

        private List<TrackPoint> sampled() {
            return points;
        }
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

    /**
     * Decrit un etat propage sous ses deux formes : vue du sol et vue de l'espace.
     *
     * <p>{@code subPoint} est obtenu par projection de la position sur l'ellipsoide en
     * ITRF, cote Orekit. Le navigateur ne le recalcule jamais : c'est la condition pour
     * que le globe reste un affichage et ne devienne pas un second propagateur.
     */
    private TrackPoint pointAt(SpacecraftState state, TopocentricFrame site) {
        TrackingCoordinates seen = trackingCoordinates(state, site);
        GeodeticPoint sub = earth.transform(state.getPosition(), state.getFrame(), state.getDate());

        return new TrackPoint(
                toInstant(state.getDate()),
                degreesInCircle(seen.getAzimuth()),
                FastMath.toDegrees(seen.getElevation()),
                seen.getRange() / 1000.0,
                new SubSatellitePoint(
                        FastMath.toDegrees(sub.getLatitude()),
                        FastMath.toDegrees(sub.getLongitude()),
                        sub.getAltitude() / 1000.0),
                false);
    }

    private TrackingCoordinates trackingCoordinates(SpacecraftState state, TopocentricFrame site) {
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
