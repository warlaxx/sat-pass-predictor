package space.nextpass.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when both Space-Track credentials are set. {@link SpaceTrackProperties} has already
 * refused half a credential at startup, so "both" and "either" agree here; a condition
 * rather than a SpEL expression, because a password may contain anything SpEL would parse.
 */
class SpaceTrackConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        return isSet(environment.getProperty("tle.space-track.identity"))
                && isSet(environment.getProperty("tle.space-track.password"));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
