package dev.abdallah.satpass.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where to look for the orekit-data directory (EOP, leap seconds, gravity models...).
 * Never committed: downloaded by scripts/fetch-orekit-data.sh.
 *
 * <h2>Why a String and not a Path</h2>
 * Binding this to {@link java.nio.file.Path} is what kept the application from starting.
 * Spring registers a {@code PathEditor} for that type, and {@code PathEditor} runs the
 * text through {@code ResourceEditor} first. In a servlet application the
 * {@code ResourceLoader} is the servlet context, so {@code ../orekit-data} becomes a
 * {@code ServletContextResource} at {@code /../orekit-data} — a path that climbs above
 * the context root, which is rejected outright:
 *
 * <pre>The resource path [/../orekit-data] has been normalized to [null] which is not valid</pre>
 *
 * It is not a {@code Path} problem — the same value binds perfectly well outside a web
 * context. That is exactly why the whole test suite stayed green while the application
 * would not start: every orbital test runs on a slice declared
 * {@code WebEnvironment.NONE}, and the one test that starts everything ran on a mock
 * servlet context. A {@code String} is handed over untouched, and
 * {@link OrekitDataDirectory} turns it into a {@code Path} — which is where that decision
 * belongs anyway, since it is the same place that decides whether the directory is usable.
 *
 * <h2>Why a list</h2>
 * Two entries, searched in order, because there are exactly two places this application
 * is launched from: {@code backend/} (Maven, the IDE) and the repository root. Setting
 * {@code OREKIT_DATA_PATH} replaces the list with the single location it names, so an
 * explicit configuration is never second-guessed by a fallback.
 *
 * @param dataPaths candidate directories, most specific first, never empty
 */
@ConfigurationProperties("orekit")
public record OrekitProperties(List<String> dataPaths) {

    public OrekitProperties {
        if (dataPaths == null || dataPaths.isEmpty()) {
            throw new IllegalArgumentException("orekit.data-paths must name at least one directory");
        }
        dataPaths = List.copyOf(dataPaths);
    }
}
