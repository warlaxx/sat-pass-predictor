package dev.abdallah.satpass.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The header of the OpenAPI documentation.
 *
 * <p>The rest is derived from the code: paths, parameters, validation bounds and response
 * schemas all come from the annotations already on the controller and from the DTO
 * records. A hand-written specification drifts from the code at the first refactoring —
 * this one cannot.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI satPassOpenApi() {
        return new OpenAPI().info(new Info()
                .title("sat-pass-predictor")
                .version("0.0.1")
                .description("""
                        Prediction of satellite passes over an observer, computed with
                        Orekit (SGP4 model) from the orbital elements published by
                        CelesTrak.

                        Every date is a UTC instant in ISO-8601 format: the user's time
                        zone is a display problem.

                        Errors follow the Problem Details format (RFC 9457). Their `type`
                        field separates causes that the HTTP status conflates — a
                        CelesTrak outage and a stale TLE both answer 503.

                        The computation is checked against an independent implementation
                        of SGP4 (Skyfield); see the Validation section of the README.""")
                .license(new License().name("MIT")));
    }
}
