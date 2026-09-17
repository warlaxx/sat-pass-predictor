package dev.abdallah.satpass.api;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.passes.PassPredictionService;
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
 * The only entry point of the API.
 *
 * <p>Every bound is set here, in annotations, and not further down in the computation. A
 * latitude of 300 degrees or a ten-year window must be refused before a propagation
 * starts: an absurd request should cost a 400, not a few seconds of SGP4.
 *
 * <p>No {@code @Validated} on the class: since Spring 6.1, MVC validates
 * constraint-carrying parameters itself, without an AOP proxy, and raises a
 * {@code HandlerMethodValidationException} which the framework already turns into a 400
 * in the Problem Details format. Adding {@code @Validated} would validate twice and
 * surface a {@code ConstraintViolationException} instead.
 *
 * <p>The {@value PassPredictionService#MAX_WINDOW_HOURS} h cap on the window is not
 * arbitrary, and it is not defined here: it belongs to
 * {@link PassPredictionService}, which refuses a longer window whoever calls it. This
 * annotation only moves the refusal forward, so that an absurd request costs a 400 rather
 * than a few seconds of SGP4.
 */
@RestController
@RequestMapping("/api/passes")
@Tag(name = "Passes", description = "Prediction of the passes visible from an observer")
public class PassController {

    private final PassQueryService passQueryService;

    public PassController(PassQueryService passQueryService) {
        this.passQueryService = passQueryService;
    }

    @GetMapping
    @Operation(
            summary = "Passes of a satellite over an observer",
            description = "The window starts at the instant of the request. The response carries"
                    + " the TLE that actually served the computation, its age, and the sampled"
                    + " track of every pass.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Passes found (the list may be empty)"),
            @ApiResponse(responseCode = "400", description = "Missing or out-of-bounds parameter",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "NORAD number absent from the CelesTrak catalogue",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "503",
                    description = "No usable TLE: CelesTrak unreachable with nothing in memory,"
                            + " or elements too old. The type field tells the two apart.",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public PassesResponse passes(
            @Parameter(description = "NORAD number of the satellite", example = "25544")
            @RequestParam @Min(1) @Max(99999) int noradId,
            @Parameter(description = "Latitude of the observer, in degrees", example = "45.7578")
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @Parameter(description = "Longitude of the observer, in degrees", example = "4.8320")
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @Parameter(description = "Altitude of the observer, in metres above the ellipsoid",
                    example = "170")
            @RequestParam(defaultValue = "0") @DecimalMin("-500.0") @DecimalMax("9000.0") double alt,
            @Parameter(description = "Length of the search window, in hours", example = "48")
            @RequestParam(defaultValue = "48") @Min(1) @Max(PassPredictionService.MAX_WINDOW_HOURS)
            int hours,
            @Parameter(description = "Minimum elevation for a pass to count, in degrees",
                    example = "10")
            @RequestParam(defaultValue = "10.0") @DecimalMin("0.0") @DecimalMax("89.0")
            double minElevation) {

        ObserverLocation observer = new ObserverLocation(lat, lon, alt);
        return PassesResponse.from(
                passQueryService.findPasses(noradId, observer, Duration.ofHours(hours), minElevation));
    }
}
