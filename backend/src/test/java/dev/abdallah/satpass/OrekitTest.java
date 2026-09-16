package dev.abdallah.satpass;

import dev.abdallah.satpass.config.OrekitConfig;
import dev.abdallah.satpass.passes.PassPredictionService;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tranche de contexte pour les tests de calcul orbital : {@code orekit-data} charge et le
 * service de prediction, rien d'autre.
 *
 * <h2>Pourquoi pas {@code @SpringBootTest} nu</h2>
 * {@code @SpringBootTest} sans {@code classes} demarre <em>toute</em> l'application. Au
 * jalon 4, un bean HTTP mal cable a fait echouer 26 tests repartis sur cinq classes dont
 * aucune ne touche au reseau, et la cause reelle etait noyee : une seule ligne disait
 * « Failed to load », les vingt-cinq autres « failure threshold exceeded ». Un test doit
 * echouer pour ce qu'il teste, sinon il ne diagnostique rien.
 *
 * <p>En nommant les classes de configuration, le contexte ne contient plus que ce dont
 * ces tests ont besoin : une panne de cablage ailleurs ne les concerne plus. Effet de
 * bord appreciable — les cinq classes partagent un seul contexte, mis en cache une fois.
 *
 * <h2>Ce que {@code @SpringBootTest(classes = ...)} garde</h2>
 * Le chargement d'{@code application.yml} et la liaison relachee des
 * {@code @ConfigurationProperties}. {@code @ContextConfiguration} nu les perdrait, et
 * {@code orekit.data-path} ne serait plus resolu. {@code WebEnvironment.NONE} evite en
 * plus de monter un contexte web dont aucun de ces tests n'a l'usage.
 *
 * <h2>La contrepartie, et comment elle est couverte</h2>
 * Plus aucun de ces tests ne verifie que l'application reelle demarre. C'est exactement
 * le defaut qu'aurait laisse passer le bug du jalon 4. {@link ApplicationStartupTest} est
 * la pour ca, et lui seul : un test qui demarre tout, qui echoue seul, et dont le message
 * designe le bean fautif.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(
        classes = {OrekitConfig.class, PassPredictionService.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
public @interface OrekitTest {
}
