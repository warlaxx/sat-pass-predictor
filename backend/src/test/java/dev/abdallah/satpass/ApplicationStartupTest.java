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
 * The only test that starts the whole application.
 *
 * <h2>Why it exists</h2>
 * The other tests run on a context slice ({@link OrekitTest}) and therefore no longer
 * check that the real assembly holds together. That is precisely what broke at milestone
 * 4: {@code RestClient.Builder} is not auto-configured by {@code starter-web} in Spring
 * Boot 4, and the context refused to start. The code compiled, every brick was correct,
 * and the application would not have started in production.
 *
 * <p>This test is therefore the necessary complement to the slicing, not a redundancy.
 * The rule: <strong>one</strong> test starts everything, and it alone fails when the
 * wiring is broken; the others stay readable.
 *
 * <h2>What it checks beyond startup</h2>
 * That the beans carrying behaviour are actually there. A context can start having
 * silently omitted a {@code @Component} — a package outside the scan, an unmet condition
 * — and that is as real a failure as an exception.
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
