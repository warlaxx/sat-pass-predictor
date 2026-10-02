package space.nextpass.delays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SpaceTrackDebutParserTest {

    /** Space-Track writes every value as a string, numbers included. */
    @Test
    void readsTheStringsSpaceTrackWrites() {
        var debuts = SpaceTrackDebutParser.parse("""
                [{"INTLDES":"2026-228B","NORAD_CAT_ID":"100961","OBJECT_TYPE":"PAYLOAD",
                  "SATNAME":"USA 700","DEBUT":"2026-10-02 12:00:00"}]""");

        assertThat(debuts).containsExactly(new SpaceTrackDebut(100961,
                Instant.parse("2026-10-02T12:00:00Z"), "2026-228B", "USA 700"));
    }

    @Test
    void acceptsTheOtherShapesOfTheSameFields() {
        var debuts = SpaceTrackDebutParser.parse("""
                [{"NORAD_CAT_ID":69998,"OBJECT_ID":"2026-159Z","OBJECT_NAME":"OBJECT Z",
                  "DEBUT":"2026-07-12T03:15:20.5"},
                 {"NORAD_CAT_ID":"100000","DEBUT":"2026-07-14"}]""");

        assertThat(debuts).containsExactly(
                new SpaceTrackDebut(69998, Instant.parse("2026-07-12T03:15:20.500Z"), "2026-159Z", "OBJECT Z"),
                new SpaceTrackDebut(100000, Instant.parse("2026-07-14T00:00:00Z"), null, null));
    }

    /** One odd row costs one data point, not the night. */
    @Test
    void skipsRowsWithoutANumberOrADebut() {
        var debuts = SpaceTrackDebutParser.parse("""
                [{"NORAD_CAT_ID":"","DEBUT":"2026-10-02 12:00:00"},
                 {"NORAD_CAT_ID":"100961","DEBUT":null},
                 {"NORAD_CAT_ID":"100962","DEBUT":"yesterday"},
                 {"NORAD_CAT_ID":"100963","DEBUT":"2026-10-02 13:00:00"}]""");

        assertThat(debuts).extracting(SpaceTrackDebut::noradId).containsExactly(100963);
    }

    @Test
    void anEmptyWeekIsAnEmptyList() {
        assertThat(SpaceTrackDebutParser.parse("[]")).isEmpty();
    }

    /** Some Space-Track errors come back as an object in a 200: that is not "nothing new". */
    @Test
    void refusesAnythingThatIsNotAList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SpaceTrackDebutParser.parse("{\"error\":\"query timeout\"}"))
                .withMessageContaining("query timeout");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SpaceTrackDebutParser.parse("<html>maintenance</html>"));
    }
}
