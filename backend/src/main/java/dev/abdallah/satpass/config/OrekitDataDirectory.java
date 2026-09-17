package dev.abdallah.satpass.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the orekit-data directory among the declared candidates.
 *
 * <p>Split out of {@link OrekitConfig}, and static, so that the resolution can be tested
 * without a Spring context. It is the only part of the configuration with anything to get
 * wrong, and it is the part that decides whether the application starts.
 */
final class OrekitDataDirectory {

    /**
     * The file whose absence produces Orekit's {@code no IERS UTC-TAI history data loaded}.
     *
     * <p>Checking that the directory <em>exists</em> is not enough, and that is not a
     * theoretical remark: an empty {@code orekit-data} passes an {@code isDirectory} test,
     * the context starts, and the failure surfaces much later in the constructor of the
     * prediction service, with a message naming neither the directory nor the property
     * that pointed at it. Requiring the marker turns that into a startup failure that says
     * what to do — which is the only reason this check exists at all.
     */
    private static final String MARKER = "tai-utc.dat";

    private OrekitDataDirectory() {
    }

    static Path resolve(List<String> candidates) {
        List<String> rejected = new ArrayList<>();

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path directory = Path.of(candidate.trim()).toAbsolutePath().normalize();
            if (Files.isRegularFile(directory.resolve(MARKER))) {
                return directory;
            }
            rejected.add(directory + (Files.isDirectory(directory)
                    ? " (directory is there, but holds no " + MARKER + ")"
                    : " (no such directory)"));
        }

        throw new IllegalStateException(describe(rejected));
    }

    /**
     * The message says what was tried and from where.
     *
     * <p>A relative candidate means nothing without the working directory it was resolved
     * against, and "orekit-data not found" sends the reader looking in the wrong place
     * when the real answer is "you launched this from somewhere else".
     */
    private static String describe(List<String> rejected) {
        return """
                orekit-data not found.
                Working directory: %s
                Tried:
                  - %s
                Fix: run scripts/fetch-orekit-data.sh from the repository root, or point \
                OREKIT_DATA_PATH at an existing orekit-data directory."""
                .formatted(Path.of("").toAbsolutePath(), String.join("\n  - ", rejected));
    }
}
