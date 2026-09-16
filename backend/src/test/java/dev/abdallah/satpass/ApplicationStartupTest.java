package dev.abdallah.satpass;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abdallah.satpass.passes.PassPredictionService;
import dev.abdallah.satpass.tle.CelestrakTleClient;
import dev.abdallah.satpass.tle.TleStore;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Le seul test qui demarre l'application entiere.
 *
 * <h2>Pourquoi il existe</h2>
 * Les autres tests tournent sur une tranche de contexte ({@link OrekitTest}) et ne
 * verifient donc plus que l'assemblage reel tient. Or c'est precisement la ce qui a casse
 * au jalon 4 : {@code RestClient.Builder} n'est pas auto-configure par
 * {@code starter-web} dans Spring Boot 4, et le contexte refusait de demarrer. Le code
 * compilait, chaque brique etait juste, et l'application n'aurait pas demarre en
 * production.
 *
 * <p>Ce test est donc le complement necessaire du decoupage, pas une redondance. La
 * regle : <strong>un</strong> test demarre tout, et lui seul echoue quand le cablage est
 * casse ; les autres restent lisibles.
 *
 * <h2>Ce qu'il verifie au-dela du demarrage</h2>
 * Que les beans qui portent le comportement sont bien la. Un contexte peut demarrer en
 * ayant silencieusement omis un {@code @Component} — un paquet hors du scan, une
 * condition non remplie — et c'est un echec aussi reel qu'une exception.
 */
@SpringBootTest
class ApplicationStartupTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theRealApplicationContextStarts() {
        assertThat(context).isNotNull();
    }

    @Test
    void everyBeanThatCarriesBehaviourIsWired() {
        assertThat(context.getBeanNamesForType(DataContext.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(PassPredictionService.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(CelestrakTleClient.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(TleStore.class)).hasSize(1);
    }
}
