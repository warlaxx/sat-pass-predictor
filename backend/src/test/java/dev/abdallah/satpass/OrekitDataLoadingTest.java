package dev.abdallah.satpass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
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
     * Verifie que l'historique des sauts de seconde (UTC-TAI) est bien charge.
     * Au 1er janvier 2017, TAI - UTC = 37 s : la meme date civile lue en UTC et
     * en TAI designe donc deux instants espaces de 37 secondes.
     * Si orekit-data n'est pas charge, Orekit ne connait aucun saut et l'ecart vaut 0.
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
     * Verifie que les parametres d'orientation terrestre (EOP) sont charges :
     * sans eux, la transformation GCRF -> ITRF est degradee et Orekit emet un
     * avertissement au lieu d'utiliser les donnees IERS.
     */
    @Test
    void earthOrientationParametersAreLoaded() {
        assertThat(dataContext.getFrames().getEOPHistory(
                org.orekit.utils.IERSConventions.IERS_2010, true).getEntries())
                .isNotEmpty();
    }
}
