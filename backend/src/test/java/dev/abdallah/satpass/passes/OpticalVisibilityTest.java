package dev.abdallah.satpass.passes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abdallah.satpass.OrekitTest;
import dev.abdallah.satpass.TleFixtures;
import dev.abdallah.satpass.domain.ObserverLocation;
import java.time.Duration;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.data.DataContext;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.springframework.beans.factory.annotation.Autowired;

@OrekitTest
class OpticalVisibilityTest {
    @Autowired PassPredictionService service;
    @Autowired DataContext context;

    @Test
    void distinguishesSunlitDaylightEclipseAndPotentiallyVisibleSamples() {
        var tle = TleFixtures.iss();
        var passes = service.predictPasses(tle, new ObserverLocation(45.7578, 4.8320, 170),
                tle.getDate().toInstant(context.getTimeScales()), Duration.ofHours(48), 10);
        var frame = context.getFrames().getITRF(IERSConventions.IERS_2010, false);
        var earth = new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING, frame);
        var site = new GeodeticPoint(Math.toRadians(45.7578), Math.toRadians(4.8320), 170);
        var observer = earth.transform(site);
        var sun = context.getCelestialBodies().getSun();
        var propagator = TLEPropagator.selectExtrapolator(tle);
        int litDay = 0, favourable = 0, shadow = 0, transition = 0;
        for (var pass : passes) {
            for (int i = 0; i < pass.track().size(); i++) {
                var point = pass.track().get(i);
                var date = new AbsoluteDate(point.instant(), context.getTimeScales().getUTC());
                var solarPosition = sun.getPosition(date, frame);
                // Independent topocentric check: project the observer-to-Sun direction
                // on the geodetic zenith, without using getTrackingCoordinates.
                double elevation = Math.asin(Vector3D.dotProduct(
                        solarPosition.subtract(observer).normalize(), site.getZenith()));
                assertThat(point.visible()).isEqualTo(point.illuminated()
                        && elevation <= Math.toRadians(-6));

                // Independent spherical angular-disc check. Away from the limb, the
                // 0.5° guard exceeds oblateness effects and must agree with Orekit's
                // ellipsoidal penumbra calculation. This catches reversed signs/frames.
                var satellite = propagator.propagate(date).getPosition(frame);
                var toSun = solarPosition.subtract(satellite);
                double clearance = Vector3D.angle(satellite.negate(), toSun)
                        - Math.asin(Constants.WGS84_EARTH_EQUATORIAL_RADIUS / satellite.getNorm())
                        - Math.asin(Constants.SUN_RADIUS / toSun.getNorm());
                if (Math.abs(clearance) > Math.toRadians(0.5)) {
                    assertThat(point.illuminated()).isEqualTo(clearance > 0);
                }
                if (point.visible()) favourable++;
                if (point.illuminated() && !point.visible()) litDay++;
                if (!point.illuminated()) shadow++;
                if (i > 0 && point.illuminated() != pass.track().get(i - 1).illuminated()) transition++;
            }
        }
        assertThat(litDay).as("sunlight alone is insufficient").isPositive();
        assertThat(favourable).as("sunlit satellite above a dark observer").isPositive();
        assertThat(shadow).as("eclipsed samples").isPositive();
        assertThat(transition).as("eclipse changes during a pass").isPositive();
    }
}
