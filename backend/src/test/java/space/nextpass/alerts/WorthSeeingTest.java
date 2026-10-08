package space.nextpass.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static space.nextpass.alerts.AlertFixtures.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import space.nextpass.domain.SatellitePass;

class WorthSeeingTest {

    static final Instant PASS = Instant.parse("2026-10-08T19:30:00Z");

    List<WorthSeeing.Pick> select(SatellitePass pass, int cloud, int minElevation, Double maxMagnitude) {
        return WorthSeeing.select(List.of(pass), clouds(cloud), EVENING, minElevation, maxMagnitude, 25);
    }

    @Test
    void aHighSunlitPassUnderAClearSkyQualifies() {
        SatellitePass pass = pass(PASS, seen(10), seen(45), seen(62), seen(30), seen(10));

        List<WorthSeeing.Pick> picks = select(pass, 10, 30, null);

        assertThat(picks).singleElement().satisfies(pick -> {
            assertThat(pick.visibleFrom()).isEqualTo(PASS);
            assertThat(pick.visibleUntil()).isEqualTo(PASS.plusSeconds(240));
            assertThat(pick.highest().elevationDeg()).isEqualTo(62);
            assertThat(pick.cloudPercent()).isEqualTo(10);
            assertThat(pick.brightest()).isNull();
        });
    }

    @Test
    void theElevationThatCountsIsTheVisibleOne() {
        // Peaks at 70° in the Earth's shadow, seen only up to 20°: a low pass to the eye.
        SatellitePass pass = pass(PASS, seen(10), seen(20), dark(70), dark(30), dark(10));

        assertThat(select(pass, 0, 30, null)).isEmpty();
        assertThat(select(pass, 0, 20, null)).singleElement()
                .satisfies(pick -> assertThat(pick.highest().elevationDeg()).isEqualTo(20));
    }

    @Test
    void aPassNeverVisibleNeverQualifies() {
        assertThat(select(pass(PASS, dark(10), dark(80), dark(10)), 0, 10, null)).isEmpty();
    }

    @Test
    void cloudsAboveTheThresholdOrNoForecastMeanNoEmail() {
        SatellitePass pass = pass(PASS, seen(10), seen(60), seen(10));

        assertThat(select(pass, 26, 30, null)).isEmpty();
        assertThat(select(pass, 25, 30, null)).hasSize(1);
        assertThat(WorthSeeing.select(List.of(pass), null, EVENING, 30, null, 100)).isEmpty();
        // Past the forecast's last step: no vouching for the sky.
        SatellitePass nextWeek = pass(PASS.plusSeconds(7 * 86_400), seen(10), seen(60), seen(10));
        assertThat(select(nextWeek, 0, 30, null)).isEmpty();
    }

    @Test
    void aKnownMagnitudeMustBeBrightEnoughAnUnknownOneIsNotHeldToIt() {
        SatellitePass faint = pass(PASS, seen(10, 1.2), seen(60, 0.4), seen(10, 1.5));
        SatellitePass bright = pass(PASS, seen(10, -1.0), seen(60, -3.1), seen(10, -0.8));
        SatellitePass unknown = pass(PASS, seen(10), seen(60), seen(10));

        assertThat(select(faint, 0, 30, -1.0)).isEmpty();
        assertThat(select(bright, 0, 30, -1.0)).singleElement()
                .satisfies(pick -> assertThat(pick.brightest()).isEqualTo(-3.1));
        assertThat(select(unknown, 0, 30, -1.0)).hasSize(1);
    }

    @Test
    void aPassWhoseVisibleStretchIsOverIsNotAnnounced() {
        SatellitePass earlier = pass(EVENING.minusSeconds(600), seen(10), seen(60), seen(10));

        assertThat(select(earlier, 0, 30, null)).isEmpty();
    }
}
