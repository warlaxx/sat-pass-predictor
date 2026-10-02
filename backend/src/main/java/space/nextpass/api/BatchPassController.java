package space.nextpass.api;

import space.nextpass.api.BatchPassesResponse.Entry;
import space.nextpass.api.PassesResponse.ObserverDto;
import space.nextpass.domain.ObserverLocation;
import space.nextpass.domain.TleSnapshot;
import space.nextpass.passes.PassPrediction;
import space.nextpass.passes.PassQueryService;
import space.nextpass.tle.TleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Several satellites over several sites in one call — a fleet over one ground station,
 * one satellite over a network of stations, or both.
 *
 * <p>Keyed only: there is no {@code /api} alias. The anonymous demo compares satellites
 * with separate calls, each against the shared budget, and a batch there would let one
 * visitor spend in one click what the budget sets aside for many.
 *
 * <p>Each satellite is resolved once for all its sites (see
 * {@link PassQueryService#findPassesForSites(int, List, Duration, double)}), and each answer goes
 * through the same prediction cache as a single call: a batch repeated within the cache's
 * lifetime costs a lookup per entry, not a propagation. The predictions are computed one
 * after the other: the bound on the batch size, not parallelism, is what keeps the
 * response time in check, and it keeps one caller from taking every core at once.
 */
@RestController
@RequestMapping("/v1/passes/batch")
@SecurityRequirement(name = "apiKey")
@Tag(name = "Versioned passes", description = "API key required; admitted requests count toward daily and minute limits")
public class BatchPassController {

    private final PassQueryService passQueryService;

    public BatchPassController(PassQueryService passQueryService) {
        this.passQueryService = passQueryService;
    }

    @GetMapping
    @Operation(
            summary = "Passes of several satellites over several sites",
            description = "Every satellite is predicted for every site, with one window and one threshold."
                    + " Counts one prediction per satellite and site against every limit of the key,"
                    + " admitted all at once or refused whole. At most " + BatchQuery.MAX_SATELLITES
                    + " satellites, " + BatchQuery.MAX_SITES + " sites and " + BatchQuery.MAX_PREDICTIONS
                    + " predictions per call. A satellite that cannot be predicted gets an error entry;"
                    + " the others are still returned.")
    @Parameters({
            @Parameter(name = "noradId", in = ParameterIn.QUERY, required = true,
                    description = "NORAD numbers, repeated or comma-separated",
                    array = @ArraySchema(schema = @Schema(type = "integer", minimum = "1", maximum = "" + TleSnapshot.MAX_NORAD_ID)),
                    example = "25544"),
            @Parameter(name = "site", in = ParameterIn.QUERY, required = true,
                    description = "Observer as lat,lon or lat,lon,alt (degrees, degrees, metres above the"
                            + " ellipsoid); repeat the parameter for several sites",
                    array = @ArraySchema(schema = @Schema(type = "string")),
                    example = "45.7578,4.8320,170"),
            @Parameter(name = "hours", in = ParameterIn.QUERY, description = "Length of the search window, in hours",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "240", defaultValue = "48")),
            @Parameter(name = "minElevation", in = ParameterIn.QUERY,
                    description = "Minimum elevation for a pass to count, in degrees",
                    schema = @Schema(type = "number", minimum = "0", maximum = "89", defaultValue = "10")),
            @Parameter(name = "track", in = ParameterIn.QUERY,
                    description = "false leaves out the sampled track of every pass, keeping its three phases",
                    schema = @Schema(type = "boolean", defaultValue = "true"))})
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "One entry per satellite and site, each a prediction or an error"),
            @ApiResponse(responseCode = "400",
                    description = "Invalid or oversized batch, or more predictions than the key's per-minute limit",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or revoked API key", content = @Content),
            @ApiResponse(responseCode = "429",
                    description = "Not enough quota left for the whole batch; Retry-After and resetsAt indicate the reset",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "API accounting unavailable", content = @Content)})
    public BatchPassesResponse batch(HttpServletRequest request) {
        BatchQuery query = BatchQuery.parse(request.getParameterMap());
        Duration window = Duration.ofHours(query.hours());
        List<Entry> results = new ArrayList<>(query.predictions());

        for (int noradId : query.noradIds()) {
            List<ObserverLocation> sites = query.sites();
            try {
                List<PassPrediction> predictions =
                        passQueryService.findPassesForSites(noradId, sites, window, query.minElevationDeg());
                for (int i = 0; i < sites.size(); i++) {
                    // No carrier: a batch mixes satellites, and one frequency for all of
                    // them would publish a Doppler shift for downlinks that do not exist.
                    // The range rate is there; scale it by each satellite's own carrier.
                    PassesResponse response = PassesResponse.from(predictions.get(i), null);
                    results.add(new Entry(noradId, i, response.observer(),
                            query.track() ? response : response.withoutTracks(), null));
                }
            } catch (TleException failure) {
                ProblemDetail problem = ApiExceptionHandler.tleProblem(failure);
                for (int i = 0; i < sites.size(); i++) {
                    results.add(new Entry(noradId, i, ObserverDto.from(sites.get(i)), null, problem));
                }
            }
        }
        return new BatchPassesResponse(query.hours(), query.minElevationDeg(), query.track(),
                results.size(), results);
    }
}
