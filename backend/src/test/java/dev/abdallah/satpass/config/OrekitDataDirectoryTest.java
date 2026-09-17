package dev.abdallah.satpass.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resolution of the orekit-data directory, without a Spring context.
 *
 * <p>This is the piece that decides whether the application starts, and it used to be
 * three lines inside a {@code @Bean} method where nothing could reach it. The bug it now
 * covers cost a launch that failed with a message about servlet resources.
 */
class OrekitDataDirectoryTest {

    private static Path orekitData(Path parent, String name) throws IOException {
        Path directory = Files.createDirectories(parent.resolve(name));
        Files.writeString(directory.resolve("tai-utc.dat"), "1961 JAN  1 =JD 2437300.5\n");
        return directory;
    }

    @Test
    void takesTheFirstCandidateThatHoldsTheData(@TempDir Path tmp) throws IOException {
        Path first = orekitData(tmp, "first");
        orekitData(tmp, "second");

        assertThat(OrekitDataDirectory.resolve(List.of(first.toString(), tmp + "/second")))
                .isEqualTo(first);
    }

    @Test
    void fallsThroughToTheNextCandidateWhenTheFirstIsNotThere(@TempDir Path tmp) throws IOException {
        Path real = orekitData(tmp, "real");

        assertThat(OrekitDataDirectory.resolve(List.of(tmp + "/absent", real.toString())))
                .isEqualTo(real);
    }

    /**
     * The defect the old check had: an empty directory passed {@code isDirectory()}, the
     * context started, and the failure surfaced much later inside Orekit as "no IERS
     * UTC-TAI history data loaded" - naming neither the directory nor the property that
     * pointed at it. An empty orekit-data is not an orekit-data.
     */
    @Test
    void refusesADirectoryThatExistsButHoldsNothing(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("empty"));
        Path real = orekitData(tmp, "real");

        assertThat(OrekitDataDirectory.resolve(List.of(tmp + "/empty", real.toString())))
                .isEqualTo(real);

        assertThatIllegalStateException()
                .isThrownBy(() -> OrekitDataDirectory.resolve(List.of(tmp + "/empty")))
                .withMessageContaining("holds no tai-utc.dat");
    }

    /**
     * A relative candidate means nothing without the directory it was resolved against.
     * "orekit-data not found" alone sends the reader looking in the wrong place when the
     * real answer is "you launched this from somewhere else".
     */
    @Test
    void theFailureNamesTheWorkingDirectoryAndEveryCandidate(@TempDir Path tmp) {
        assertThatIllegalStateException()
                .isThrownBy(() -> OrekitDataDirectory.resolve(List.of(tmp + "/a", tmp + "/b")))
                .withMessageContaining("Working directory: " + Path.of("").toAbsolutePath())
                .withMessageContaining(tmp + "/a")
                .withMessageContaining(tmp + "/b")
                .withMessageContaining("OREKIT_DATA_PATH");
    }

    @Test
    void resolvesRelativeCandidatesAgainstTheWorkingDirectory() {
        // Deliberately not "../orekit-data": Maven runs this from backend/, where that one
        // exists, and the test would then assert nothing.
        String relative = "../not-an-orekit-data-directory";

        assertThatIllegalStateException()
                .isThrownBy(() -> OrekitDataDirectory.resolve(List.of(relative)))
                .withMessageContaining(Path.of(relative).toAbsolutePath().normalize().toString());
    }

    @Test
    void ignoresBlankEntries(@TempDir Path tmp) throws IOException {
        Path real = orekitData(tmp, "real");

        assertThat(OrekitDataDirectory.resolve(List.of("  ", real.toString()))).isEqualTo(real);
    }
}
