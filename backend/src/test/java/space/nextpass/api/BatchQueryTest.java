package space.nextpass.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import space.nextpass.domain.ObserverLocation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The parser alone. It decides both what a batch computes and what it is billed, so its
 * rules are pinned here rather than inferred from the controller's responses.
 */
class BatchQueryTest {

    private static Map<String, String[]> query(String... pairs) {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String name = pairs[i];
            String value = pairs[i + 1];
            parameters.merge(name, new String[]{value}, (old, added) -> {
                String[] merged = java.util.Arrays.copyOf(old, old.length + 1);
                merged[old.length] = added[0];
                return merged;
            });
        }
        return parameters;
    }

    @Test
    void parsesRepeatedAndCommaSeparatedSatellitesAndSitesWithDefaults() {
        BatchQuery batch = BatchQuery.parse(query(
                "noradId", "25544,20580", "noradId", "48274",
                "site", "45.7578,4.8320,170", "site", "-33.9, 18.4"));

        assertThat(batch.noradIds()).containsExactly(25544, 20580, 48274);
        assertThat(batch.sites()).containsExactly(
                new ObserverLocation(45.7578, 4.8320, 170), new ObserverLocation(-33.9, 18.4, 0));
        assertThat(batch.hours()).isEqualTo(48);
        assertThat(batch.minElevationDeg()).isEqualTo(10.0);
        assertThat(batch.track()).isTrue();
        assertThat(batch.predictions()).isEqualTo(6);
    }

    @Test
    void dropsDuplicatesInOrderOfFirstAppearanceSoTheyAreNotBilledTwice() {
        BatchQuery batch = BatchQuery.parse(query(
                "noradId", "20580,25544", "noradId", "20580",
                "site", "45,4", "site", "45,4,0", "site", "10,10"));

        assertThat(batch.noradIds()).containsExactly(20580, 25544);
        assertThat(batch.sites()).hasSize(2);
        assertThat(BatchQuery.predictionCount(query(
                "noradId", "20580,25544,20580", "site", "45,4", "site", "45,4"))).isEqualTo(2);
    }

    @Test
    void readsWindowThresholdAndTrackOption() {
        BatchQuery batch = BatchQuery.parse(query("noradId", "25544", "site", "0,0",
                "hours", "240", "minElevation", "30.5", "track", "false"));

        assertThat(batch.hours()).isEqualTo(240);
        assertThat(batch.minElevationDeg()).isEqualTo(30.5);
        assertThat(batch.track()).isFalse();
    }

    @Test
    void acceptsExactlyTheCapsAndRefusesOneMore() {
        String tenSatellites = "1,2,3,4,5,6,7,8,9,10";
        assertThat(BatchQuery.parse(query("noradId", tenSatellites, "site", "0,0")).predictions()).isEqualTo(10);
        assertThatThrownBy(() -> BatchQuery.parse(query("noradId", tenSatellites + ",11", "site", "0,0")))
                .hasMessageContaining("At most 10 distinct satellites");

        String[] tenSites = new String[20];
        for (int i = 0; i < 10; i++) {
            tenSites[2 * i] = "site";
            tenSites[2 * i + 1] = i + ",0";
        }
        Map<String, String[]> sites = query(tenSites);
        sites.put("noradId", new String[]{"25544"});
        assertThat(BatchQuery.parse(sites).predictions()).isEqualTo(10);
        sites.put("site", java.util.stream.IntStream.rangeClosed(0, 10).mapToObj(i -> i + ",0").toArray(String[]::new));
        assertThatThrownBy(() -> BatchQuery.parse(sites)).hasMessageContaining("At most 10 distinct sites");

        // 5 × 5 fits, 5 × 6 does not, although each list is within its own cap.
        assertThat(BatchQuery.parse(query("noradId", "1,2,3,4,5",
                "site", "0,0", "site", "1,0", "site", "2,0", "site", "3,0", "site", "4,0")).predictions()).isEqualTo(25);
        assertThatThrownBy(() -> BatchQuery.parse(query("noradId", "1,2,3,4,5",
                "site", "0,0", "site", "1,0", "site", "2,0", "site", "3,0", "site", "4,0", "site", "5,0")))
                .hasMessageContaining("At most 25 predictions").hasMessageContaining("asks for 30");
    }

    /** Six-digit numbers have been given since July 2026; Alpha-5 ends at 339999. */
    @Test
    void acceptsSixDigitNumbersUpToTheLastATleCanCarry() {
        BatchQuery batch = BatchQuery.parse(query("noradId", "100685,100961", "noradId", "339999", "site", "0,0"));

        assertThat(batch.noradIds()).containsExactly(100685, 100961, 339999);
    }

    @Test
    void refusesWhatTheSingleEndpointRefuses() {
        String[][] invalid = {
                {"site", "0,0"},
                {"noradId", "25544"},
                {"noradId", "0", "site", "0,0"},
                {"noradId", "340000", "site", "0,0"},
                {"noradId", "25544,", "site", "0,0"},
                {"noradId", "iss", "site", "0,0"},
                {"noradId", "25544", "site", "91,0"},
                {"noradId", "25544", "site", "0,-180.5"},
                {"noradId", "25544", "site", "0,0,9001"},
                {"noradId", "25544", "site", "NaN,0"},
                {"noradId", "25544", "site", "0,Infinity"},
                {"noradId", "25544", "site", "45"},
                {"noradId", "25544", "site", "45,4,170,1"},
                {"noradId", "25544", "site", "0,0", "hours", "241"},
                {"noradId", "25544", "site", "0,0", "hours", "0"},
                {"noradId", "25544", "site", "0,0", "hours", "1.5"},
                {"noradId", "25544", "site", "0,0", "minElevation", "90"},
                {"noradId", "25544", "site", "0,0", "minElevation", "-1"},
                {"noradId", "25544", "site", "0,0", "hours", "24", "hours", "48"},
                {"noradId", "25544", "site", "0,0", "track", "yes"},
        };
        for (String[] pairs : invalid) {
            assertThatThrownBy(() -> BatchQuery.parse(query(pairs)))
                    .as(String.join(" ", pairs))
                    .isInstanceOf(IllegalArgumentException.class);
            // Admission charges an invalid batch like one refused call, never its size.
            assertThat(BatchQuery.predictionCount(query(pairs))).isEqualTo(1);
        }
    }
}
