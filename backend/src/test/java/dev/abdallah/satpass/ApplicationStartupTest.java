package dev.abdallah.satpass;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abdallah.satpass.passes.PassPredictionService;
import dev.abdallah.satpass.config.TleProperties;
import dev.abdallah.satpass.tle.FallbackTleClient;
import dev.abdallah.satpass.tle.TleClient;
import dev.abdallah.satpass.tle.TleStore;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The only test that starts the whole application, on a real servlet container.
 *
 * <h2>Why it exists</h2>
 * The other tests run on a context slice ({@link OrekitTest}) and therefore no longer
 * check that the real assembly holds together. That is precisely what broke at milestone
 * 4: {@code RestClient.Builder} is not auto-configured by {@code starter-web} in Spring
 * Boot 4, and the context refused to start. The code compiled, every brick was correct,
 * and the application would not have started in production.
 *
 * <h2>Why a real web environment, and not the mock one</h2>
 * Because a mock servlet context is not a servlet container, and the difference is not
 * academic. With {@code WebEnvironment.MOCK} this class stayed green for three milestones
 * while {@code mvn spring-boot:run} could not start at all: {@code orekit.data-path} was
 * bound to a {@link java.nio.file.Path}, Spring's {@code PathEditor} resolved it through
 * the servlet {@code ResourceLoader}, and {@code ../orekit-data} became
 * {@code /../orekit-data} - a path above the context root, rejected outright. Every
 * orbital test runs on {@code WebEnvironment.NONE} and never saw it; this one ran on a
 * mock and did not see it either.
 *
 * <p>A test that claims to start "the whole application" has to start the thing that is
 * actually shipped. {@code RANDOM_PORT} costs about a second and buys back the only
 * failure mode the slices cannot see.
 *
 * <h2>What it checks beyond startup</h2>
 * That the beans carrying behaviour are actually there. A context can start having
 * silently omitted a {@code @Component} - a package outside the scan, an unmet condition
 * - and that is as real a failure as an exception.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationStartupTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    TleClient tleClient;

    @Autowired
    TleProperties tleProperties;

    @Test
    void theRealApplicationContextStarts() {
        assertThat(context).isNotNull();
    }

    @Test
    void everyBeanThatCarriesBehaviourIsWired() {
        assertThat(context.getBeanNamesForType(DataContext.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(PassPredictionService.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(TleClient.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(TleStore.class)).hasSize(1);
    }

    /**
     * Every configured source is actually wired. The list in {@code application.yml} is
     * the whole point of the chain, and a binding that silently kept one entry would look
     * exactly like a healthy application — right up to the day the first source goes
     * unreachable, which is the day it is needed.
     */
    @Test
    void everyConfiguredTleSourceIsWiredIntoTheChain() {
        assertThat(tleProperties.baseUrls()).hasSizeGreaterThan(1);
        assertThat(tleClient).isInstanceOf(FallbackTleClient.class);
        assertThat(((FallbackTleClient) tleClient).size())
                .isEqualTo(tleProperties.baseUrls().size());
    }
}
