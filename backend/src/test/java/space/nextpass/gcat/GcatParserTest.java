package space.nextpass.gcat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import space.nextpass.gcat.GcatDate.Precision;

/** Against real GCAT rows: see the excerpts in src/test/resources/gcat. */
class GcatParserTest {

    static Map<String, GcatObject> parse(String resource) {
        Map<String, GcatObject> objects = new LinkedHashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(
                GcatParserTest.class.getResourceAsStream("/gcat/" + resource), StandardCharsets.UTF_8))) {
            GcatParser.Result result = GcatParser.parse(reader, o -> objects.put(o.jcat(), o));
            assertThat(result.rows()).isEqualTo(objects.size());
            assertThat(result.unparsed()).isZero();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return objects;
    }

    @Test
    void readsColumnsByNameAndTrimsThePadding() {
        GcatObject first = parse("satcat.tsv").get("S00001");

        assertThat(first.satcat()).isEqualTo(1);
        assertThat(first.launchTag()).isEqualTo("1957 ALP");
        assertThat(first.type()).isEqualTo("R2");
        assertThat(first.name()).isEqualTo("8K71PS No. M1-10 Stage 2");
        assertThat(first.launch()).isEqualTo(new GcatDate(Instant.parse("1957-10-04T00:00:00Z"), Precision.DAY, false));
        assertThat(first.parent()).isNull();
        assertThat(first.decay().uncertain()).isTrue();
        assertThat(first.massKg()).isEqualTo(7790.0);
        assertThat(first.perigeeKm()).isEqualTo(214.0);
        assertThat(first.apogeeKm()).isEqualTo(938.0);
        assertThat(first.inclinationDeg()).isEqualTo(65.10);
        assertThat(first.status()).isEqualTo("R");
    }

    @Test
    void keepsTheParentIdentifierAndGcatsWholeCell() {
        Map<String, GcatObject> objects = parse("satcat.tsv");

        assertThat(objects.get("S03600").parent()).isEqualTo("S03504");
        assertThat(objects.get("S03600").parentText()).isEqualTo("S03504*");
        assertThat(objects.get("S16013").parent()).isEqualTo("S15992");
        assertThat(objects.get("S16013").parentText()).isEqualTo("S15992  AL");
    }

    @Test
    void sixDigitCatalogueNumbersComeFromTheSecondFile() {
        Map<String, GcatObject> objects = parse("satcat100k.tsv");

        assertThat(objects.get("S100961").satcat()).isEqualTo(100961);
        assertThat(objects.get("S100000").separation())
                .isEqualTo(new GcatDate(Instant.parse("2026-07-01T00:00:00Z"), Precision.MONTH, false));
    }

    @Test
    void aSeparationIsReleasedByASatelliteOnAnotherDayThanItsLaunch() {
        Map<String, GcatObject> objects = new LinkedHashMap<>(parse("satcat.tsv"));
        objects.putAll(parse("satcat100k.tsv"));

        // The excerpts hold none of the parents: each is judged as an uncatalogued one.
        List<String> separations = objects.values().stream()
                .filter(o -> o.isSeparation((GcatObject.Parent) null)).map(GcatObject::jcat).toList();

        // Not S00001 (no parent), S69998 (released on its launch day by an uncatalogued
        // parent, the stage), S100961 (its parent is a launch vehicle), nor S03600 (debris of
        // Kosmos-249 on its launch day). SeparationRuleTest holds the cases with a parent.
        assertThat(separations).containsExactly(
                "S16013", "S69237", "S69328", "S69731", "S100000", "S100810");
    }

    @Test
    void anOddCellIsCountedNotFatal() {
        String file = """
                #JCAT\tSatcat\tLaunch_Tag\tPiece\tType\tName\tPLName\tLDate\tParent\tSDate\tPrimary\tDDate\tStatus\tOwner\tState\tMass\tPerigee\tApogee\tInc\tOpOrbit\tAltNames
                S1\t1\t-\t-\tP\tX\t-\tsoon\t-\t-\tEarth\t-\tO\t-\t-\theavy\t1\t2\t3\tLEO\t-
                """;
        List<GcatObject> objects = new ArrayList<>();

        GcatParser.Result result = GcatParser.parse(new BufferedReader(new StringReader(file)), objects::add);

        assertThat(result).isEqualTo(new GcatParser.Result(1, 2));
        assertThat(objects.getFirst().launchText()).isEqualTo("soon");
        assertThat(objects.getFirst().launch()).isNull();
        assertThat(objects.getFirst().massKg()).isNull();
    }

    @Test
    void anEscapeTrajectoryHasAnInfiniteApogee() {
        String file = """
                #JCAT\tSatcat\tLaunch_Tag\tPiece\tType\tName\tPLName\tLDate\tParent\tSDate\tPrimary\tDDate\tStatus\tOwner\tState\tMass\tPerigee\tApogee\tInc\tOpOrbit\tAltNames
                S1\t1\t-\t-\tP\tX\t-\t-\t-\t-\tEarth\t-\tE\t-\t-\t-\t180\t     Inf\t30\tHCO\t-
                """;
        List<GcatObject> objects = new ArrayList<>();

        GcatParser.Result result = GcatParser.parse(new BufferedReader(new StringReader(file)), objects::add);

        assertThat(result.unparsed()).isZero();
        assertThat(objects.getFirst().apogeeKm()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void aMissingColumnFailsTheFile() {
        String file = "#JCAT\tSatcat\tName\nS1\t1\tX\n";

        assertThatThrownBy(() -> GcatParser.parse(new BufferedReader(new StringReader(file)), o -> {}))
                .isInstanceOf(GcatImportException.class)
                .hasMessageContaining("Launch_Tag");
    }
}
