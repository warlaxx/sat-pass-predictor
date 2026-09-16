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
 * <p>Sans ces fichiers, le premier appel a TimeScalesFactory.getUTC() leve une
 * OrekitException ("no IERS UTC-TAI history data loaded"). On echoue donc au
 * demarrage, avec un message explicite, plutot qu'au premier calcul.
 *
 * <h2>Enregistrement idempotent</h2>
 * {@link DataContext#getDefault()} est un singleton de JVM, pas un bean : son
 * gestionnaire de fournisseurs survit au contexte Spring qui l'a rempli. Un simple
 * {@code addProvider} empilerait donc un fournisseur de plus a chaque contexte cree dans
 * la meme JVM — plusieurs contextes de test, un redemarrage a chaud — et les memes
 * fichiers EOP seraient presentes deux fois aux chargeurs d'Orekit.
 *
 * <p>D'ou le {@code clearProviders()} prealable : l'etat final est le meme quel que soit
 * le nombre d'appels, un fournisseur pointant sur le repertoire configure. C'est correct
 * ici parce que cette application est la seule a configurer Orekit ; ca cesserait de
 * l'etre le jour ou une autre source de donnees s'ajouterait, et il faudrait alors
 * enregistrer les fournisseurs au meme endroit plutot que de partir du defaut global.
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
        context.getDataProvidersManager().clearProviders();
        context.getDataProvidersManager().addProvider(new DirectoryCrawler(dir));
        return context;
    }
}
