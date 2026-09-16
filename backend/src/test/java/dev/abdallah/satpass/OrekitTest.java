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
 * Context slice for orbital computation tests: {@code orekit-data} loaded and the
 * prediction service, nothing else.
 *
 * <h2>Why not a bare {@code @SpringBootTest}</h2>
 * {@code @SpringBootTest} without {@code classes} starts the <em>whole</em> application.
 * At milestone 4, one badly wired HTTP bean failed 26 tests across five classes, none of
 * which touches the network, and the real cause was buried: a single line said "Failed to
 * load", the other twenty-five said "failure threshold exceeded". A test must fail for
 * what it tests, otherwise it diagnoses nothing.
 *
 * <p>By naming the configuration classes, the context holds only what these tests need: a
 * wiring failure elsewhere no longer concerns them. Welcome side effect — the five
 * classes share a single context, cached once.
 *
 * <h2>What {@code @SpringBootTest(classes = ...)} keeps</h2>
 * The loading of {@code application.yml} and the relaxed binding of
 * {@code @ConfigurationProperties}. A bare {@code @ContextConfiguration} would lose them,
 * and {@code orekit.data-path} would no longer resolve. {@code WebEnvironment.NONE} also
 * avoids standing up a web context none of these tests has any use for.
 *
 * <h2>The trade-off, and how it is covered</h2>
 * None of these tests checks any more that the real application starts. That is exactly
 * the defect the milestone 4 bug would have slipped through. {@link ApplicationStartupTest}
 * exists for that, and it alone: one test that starts everything, that fails on its own,
 * and whose message names the offending bean.
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
