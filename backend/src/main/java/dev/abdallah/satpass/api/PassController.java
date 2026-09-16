package dev.abdallah.satpass.api;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.passes.PassQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le seul point d'entree de l'API.
 *
 * <p>Toutes les bornes sont posees ici, en annotations, et pas plus loin dans le calcul.
 * Une latitude de 300 degres ou une fenetre de dix ans doivent etre refusees avant qu'une
 * propagation ne demarre : le cout d'une requete absurde doit rester celui d'un 400, pas
 * celui de quelques secondes de SGP4.
 *
 * <p>Pas de {@code @Validated} sur la classe : depuis Spring 6.1, MVC valide lui-meme les
 * parametres porteurs de contraintes, sans proxy AOP, et leve une
 * {@code HandlerMethodValidationException} que le cadre traduit deja en 400 au format
 * Problem Details. Ajouter {@code @Validated} ferait valider deux fois et remonterait une
 * {@code ConstraintViolationException} a la place.
 *
 * <p>Le plafond de {@value #MAX_HOURS} h sur la fenetre n'est pas arbitraire. Au-dela,
 * l'erreur de SGP4 depasse largement la precision affichee, et la reponse grossit d'un
 * passage toutes les 90 minutes environ, chacun portant une quarantaine de points.
 * Dix jours est deja la limite de ce qui a un sens physique.
 */
@RestController
@RequestMapping("/api/passes")
@Tag(name = "Passages", description = "Prediction des passages visibles depuis un observateur")
public class PassController {

    /** Fenetre maximale, en heures. Voir la javadoc de la classe. */
    public static final int MAX_HOURS = 240;

    private final PassQueryService passQueryService;

    public PassController(PassQueryService passQueryService) {
        this.passQueryService = passQueryService;
    }

    @GetMapping
    @Operation(
            summary = "Passages d'un satellite au-dessus d'un observateur",
            description = "La fenetre demarre a l'instant de la requete. La reponse porte le"
                    + " TLE qui a reellement servi au calcul, son age, et la trajectoire"
                    + " echantillonnee de chaque passage.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Passages trouves (la liste peut etre vide)"),
            @ApiResponse(responseCode = "400", description = "Parametre manquant ou hors bornes",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "Numero NORAD absent du catalogue CelesTrak",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "503",
                    description = "Aucun TLE exploitable : CelesTrak injoignable sans rien en"
                            + " memoire, ou elements trop anciens. Le champ type distingue les deux.",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public PassesResponse passes(
            @Parameter(description = "Numero NORAD du satellite", example = "25544")
            @RequestParam @Min(1) @Max(99999) int noradId,
            @Parameter(description = "Latitude de l'observateur, en degres", example = "45.7578")
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @Parameter(description = "Longitude de l'observateur, en degres", example = "4.8320")
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @Parameter(description = "Altitude de l'observateur, en metres au-dessus de l'ellipsoide",
                    example = "170")
            @RequestParam(defaultValue = "0") @DecimalMin("-500.0") @DecimalMax("9000.0") double alt,
            @Parameter(description = "Duree de la fenetre de recherche, en heures", example = "48")
            @RequestParam(defaultValue = "48") @Min(1) @Max(MAX_HOURS) int hours,
            @Parameter(description = "Elevation minimale pour qu'un passage compte, en degres",
                    example = "10")
            @RequestParam(defaultValue = "10.0") @DecimalMin("0.0") @DecimalMax("89.0")
            double minElevation) {

        ObserverLocation observer = new ObserverLocation(lat, lon, alt);
        return PassesResponse.from(
                passQueryService.findPasses(noradId, observer, Duration.ofHours(hours), minElevation));
    }
}
