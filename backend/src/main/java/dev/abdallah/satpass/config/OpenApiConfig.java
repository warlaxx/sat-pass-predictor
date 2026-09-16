package dev.abdallah.satpass.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * En-tete de la documentation OpenAPI.
 *
 * <p>Le reste est deduit du code : chemins, parametres, bornes de validation et schema
 * des reponses viennent des annotations deja presentes sur le controleur et des records
 * du DTO. Une specification ecrite a la main derive du code des le premier refactoring —
 * ici elle ne peut pas.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI satPassOpenApi() {
        return new OpenAPI().info(new Info()
                .title("sat-pass-predictor")
                .version("0.0.1")
                .description("""
                        Prediction des passages de satellites au-dessus d'un observateur,
                        calculee avec Orekit (modele SGP4) a partir des elements orbitaux
                        publies par CelesTrak.

                        Toutes les dates sont des instants UTC au format ISO-8601 : le
                        fuseau de l'utilisateur est un probleme d'affichage.

                        Les erreurs suivent le format Problem Details (RFC 9457). Leur
                        champ `type` distingue des causes que le code HTTP confond — une
                        panne de CelesTrak et un TLE trop ancien renvoient tous deux 503.

                        Le calcul est verifie contre une implementation independante de
                        SGP4 (Skyfield) ; voir la section Validation du README.""")
                .license(new License().name("MIT")));
    }
}
