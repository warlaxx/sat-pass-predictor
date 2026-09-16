package dev.abdallah.satpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.utils.IERSConventions;
import org.springframework.beans.factory.annotation.Autowired;

@OrekitTest
class OrekitDataLoadingTest {

    @Autowired
    DataContext dataContext;

    @Test
    void utcTimeScaleIsLoaded() {
        TimeScale utc = dataContext.getTimeScales().getUTC();
        assertThat(utc).isNotNull();
        assertThat(utc.getName()).isEqualTo("UTC");
    }

    /**
     * Checks that the leap second history (UTC-TAI) is loaded. On 1 January 2017,
     * TAI - UTC = 37 s: the same civil date read in UTC and in TAI therefore denotes two
     * instants 37 seconds apart. Without orekit-data, Orekit knows of no leap second and
     * the gap is zero.
     */
    @Test
    void leapSecondHistoryIsLoaded() {
        TimeScale utc = dataContext.getTimeScales().getUTC();
        TimeScale tai = dataContext.getTimeScales().getTAI();

        AbsoluteDate sameCivilDateInUtc = new AbsoluteDate(2017, 1, 1, 0, 0, 0.0, utc);
        AbsoluteDate sameCivilDateInTai = new AbsoluteDate(2017, 1, 1, 0, 0, 0.0, tai);

        assertThat(sameCivilDateInUtc.durationFrom(sameCivilDateInTai))
                .isCloseTo(37.0, within(1e-9));
    }

    /**
     * Checks that the Earth orientation parameters are loaded: without them the
     * GCRF to ITRF transform is degraded and Orekit issues a warning instead of using the
     * IERS data.
     */
    @Test
    void earthOrientationParametersAreLoaded() {
        assertThat(dataContext.getFrames().getEOPHistory(IERSConventions.IERS_2010, true).getEntries())
                .isNotEmpty();
    }
}
