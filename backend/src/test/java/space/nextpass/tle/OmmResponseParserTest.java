package space.nextpass.tle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

import space.nextpass.OrekitTest;
import space.nextpass.TleFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.TimeScale;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The OMM conversion against CelesTrak's own answers, captured on 2 October 2026.
 *
 * <p>The reference is not a hand computation: it is the TLE CelesTrak published for the
 * same element set of the ISS. If the conversion gets a unit or a factor wrong, the two
 * disagree here, not in a pass prediction three layers further down.
 */
@OrekitTest
class OmmResponseParserTest {

    private static final String ENDPOINT = "https://celestrak.test";

    @Autowired
    DataContext dataContext;

    private TimeScale utc;

    @BeforeEach
    void setUp() {
        utc = dataContext.getTimeScales().getUTC();
    }

    private TLE convert(int noradId, String body) {
        String[] lines = OmmResponseParser.toThreeLines(noradId, ENDPOINT, body, utc).split("\n");
        assertThat(lines).hasSize(3);
        return new TLE(lines[1], lines[2], utc);
    }

    @Test
    void givesTheElementsOfTheTleCelesTrakPublishesForTheSameElementSet() {
        TLE fromOmm = convert(25544, TleFixtures.ISS_2026_OMM);
        TLE published = new TLE(TleFixtures.ISS_2026_LINE1, TleFixtures.ISS_2026_LINE2, utc);

        assertThat(fromOmm.getSatelliteNumber()).isEqualTo(25544);
        // The epoch of a line has eight decimals of a day: 0.86 ms.
        assertThat(fromOmm.getDate().durationFrom(published.getDate())).isCloseTo(0.0, within(1e-3));
        assertThat(fromOmm.getMeanMotion()).isEqualTo(published.getMeanMotion());
        assertThat(fromOmm.getE()).isEqualTo(published.getE());
        assertThat(fromOmm.getI()).isEqualTo(published.getI());
        assertThat(fromOmm.getRaan()).isEqualTo(published.getRaan());
        assertThat(fromOmm.getPerigeeArgument()).isEqualTo(published.getPerigeeArgument());
        assertThat(fromOmm.getMeanAnomaly()).isEqualTo(published.getMeanAnomaly());
        assertThat(fromOmm.getBStar()).isEqualTo(published.getBStar());
        assertThat(fromOmm.getMeanMotionFirstDerivative())
                .isEqualTo(published.getMeanMotionFirstDerivative());
        assertThat(fromOmm.getRevolutionNumberAtEpoch()).isEqualTo(58831);
        assertThat(fromOmm.getLaunchYear()).isEqualTo(1998);
        assertThat(fromOmm.getLaunchNumber()).isEqualTo(67);
        assertThat(fromOmm.getLaunchPiece()).isEqualTo("A");
        assertThat(fromOmm.getLine2()).isEqualTo(TleFixtures.ISS_2026_LINE2);
    }

    /** The trap Orekit's own OMM parser falls into: see the class javadoc of the parser. */
    @Test
    void copiesTheMeanMotionDerivativeAsCelesTrakWritesIt() {
        String line1 = convert(100534, TleFixtures.STARLINK_100534_OMM).getLine1();

        assertThat(line1.substring(33, 43)).isEqualTo(" .00112611");
    }

    @Test
    void writesSixDigitNumbersInAlphaFive() {
        String[] lines = OmmResponseParser.toThreeLines(
                100534, ENDPOINT, TleFixtures.STARLINK_100534_OMM, utc).split("\n");

        assertThat(lines[0]).isEqualTo("STARLINK-38244");
        assertThat(lines[1]).isEqualTo(TleFixtures.STARLINK_100534_LINE1);
        assertThat(lines[2]).isEqualTo(TleFixtures.STARLINK_100534_LINE2);
        assertThat(new TLE(lines[1], lines[2], utc).getSatelliteNumber()).isEqualTo(100534);
        assertThat(TLE.isFormatOK(lines[1], lines[2])).isTrue();
    }

    /** {@code Z9999}: nothing above it fits in a line, and nothing is invented. */
    @Test
    void refusesANumberBeyondAlphaFive() {
        String beyond = TleFixtures.STARLINK_100534_OMM.replace("100534", "340000");

        assertThatExceptionOfType(TleUnavailableException.class)
                .isThrownBy(() -> OmmResponseParser.toThreeLines(340000, ENDPOINT, beyond, utc))
                .withMessageContaining(ENDPOINT);
    }

    /** No designator to read: zeros in the columns, and the elements are still usable. */
    @Test
    void acceptsAnObjectWithoutAnInternationalDesignator() {
        String anonymous = TleFixtures.STARLINK_100534_OMM.replace("\"2026-200A\"", "\"\"");

        TLE tle = convert(100534, anonymous);

        assertThat(tle.getSatelliteNumber()).isEqualTo(100534);
        assertThat(tle.getLaunchNumber()).isZero();
    }

    @Test
    void refusesWhatIsNotAnArrayOfOneOmm() {
        for (String body : new String[] {
                "<html><body>Too many requests</body></html>",
                "GP data has not updated since your last successful download",
                "[]",
                "{}",
                TleFixtures.STARLINK_100534_OMM.replace("]", "," + TleFixtures.STARLINK_100534_OMM.strip()
                        .substring(1)),
                "[{\"NORAD_CAT_ID\":100534"}) {
            assertThatExceptionOfType(TleUnavailableException.class)
                    .as(body)
                    .isThrownBy(() -> OmmResponseParser.toThreeLines(100534, ENDPOINT, body, utc));
        }
    }

    /** A missing field is refused, not read as zero: a zero eccentricity is an orbit too. */
    @Test
    void refusesAnOmmWithAMissingOrMistypedElement() {
        for (String body : new String[] {
                TleFixtures.STARLINK_100534_OMM.replace("\"ECCENTRICITY\":0.00032284,", ""),
                TleFixtures.STARLINK_100534_OMM.replace("\"MEAN_MOTION\":15.7759124", "\"MEAN_MOTION\":\"15.7759124\""),
                TleFixtures.STARLINK_100534_OMM.replace("\"EPOCH\":\"2026-10-01T22:11:24.452448\"", "\"EPOCH\":\"yesterday\""),
                TleFixtures.STARLINK_100534_OMM.replace("\"OBJECT_NAME\":\"STARLINK-38244\",", "")}) {
            assertThatExceptionOfType(TleUnavailableException.class)
                    .as(body)
                    .isThrownBy(() -> OmmResponseParser.toThreeLines(100534, ENDPOINT, body, utc));
        }
    }
}
