package dev.abdallah.satpass.config;

import java.io.File;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.data.LazyLoadedDataContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers orekit-data in Orekit's default data context.
 *
 * <p>Without those files, the first call to TimeScalesFactory.getUTC() raises an
 * OrekitException ("no IERS UTC-TAI history data loaded"). We therefore fail at startup,
 * with an explicit message, rather than at the first computation.
 *
 * <h2>Idempotent registration</h2>
 * {@link DataContext#getDefault()} is a JVM singleton, not a bean: its provider manager
 * outlives the Spring context that filled it. A plain {@code addProvider} would stack one
 * more provider for every context created in the same JVM — several test contexts, a hot
 * restart — and the same EOP files would be handed twice to Orekit's loaders.
 *
 * <p>Hence the {@code clearProviders()} beforehand: the end state is the same however
 * many times this runs, one provider pointing at the configured directory. That is
 * correct here because this application is the only thing configuring Orekit.
 *
 * <h2>Why the global context at all</h2>
 * Orekit does accept an explicit {@code DataContext} on most of its entry points, and
 * owning a private {@link LazyLoadedDataContext} would avoid mutating a JVM singleton
 * altogether. It is not free: every call site would then have to thread the context
 * through — {@code new TLE(line1, line2, ...)}, {@code TLEPropagator.selectExtrapolator},
 * frame and time-scale lookups — and any library code falling back on the default would
 * silently read no data at all. The day a second data source appears, that is the change
 * to make; until then the global context is the smaller of the two evils, and it is
 * documented rather than implicit.
 */
@Configuration
@EnableConfigurationProperties(OrekitProperties.class)
public class OrekitConfig {

    @Bean
    public DataContext orekitDataContext(OrekitProperties properties) {
        File dir = properties.dataPath().toFile();
        if (!dir.isDirectory()) {
            throw new IllegalStateException(
                    "orekit-data not found: " + dir.getAbsolutePath()
                            + " — run scripts/fetch-orekit-data.sh first");
        }
        LazyLoadedDataContext context = DataContext.getDefault();
        context.getDataProvidersManager().clearProviders();
        context.getDataProvidersManager().addProvider(new DirectoryCrawler(dir));
        return context;
    }
}
