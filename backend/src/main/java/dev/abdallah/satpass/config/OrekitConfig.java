package dev.abdallah.satpass.config;

import java.io.File;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.data.LazyLoadedDataContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enregistre orekit-data dans le contexte de donnees par defaut d'Orekit.
 *
 * Sans ces fichiers, le premier appel a TimeScalesFactory.getUTC() leve une
 * OrekitException ("no IERS UTC-TAI history data loaded"). On echoue donc au
 * demarrage, avec un message explicite, plutot qu'au premier calcul.
 */
@Configuration
@EnableConfigurationProperties(OrekitProperties.class)
public class OrekitConfig {

    @Bean
    public DataContext orekitDataContext(OrekitProperties properties) {
        File dir = properties.dataPath().toFile();
        if (!dir.isDirectory()) {
            throw new IllegalStateException(
                    "orekit-data introuvable : " + dir.getAbsolutePath()
                            + " — lance d'abord scripts/fetch-orekit-data.sh");
        }
        LazyLoadedDataContext context = DataContext.getDefault();
        context.getDataProvidersManager().addProvider(new DirectoryCrawler(dir));
        return context;
    }
}
