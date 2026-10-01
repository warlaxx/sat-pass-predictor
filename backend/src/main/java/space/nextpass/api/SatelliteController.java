package space.nextpass.api;

import space.nextpass.catalog.SatelliteCatalog;
import space.nextpass.catalog.SatelliteEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Turns a satellite name into NORAD numbers, for the search box.
 *
 * <p>Not a {@link PassController}, so neither the API key nor the quota applies: a lookup
 * is a scan of an in-memory list, and charging for it would charge for typing. The
 * prediction the chosen number leads to is still metered where it always was.
 */
@RestController
@RequestMapping("/api/satellites")
@Tag(name = "Satellites", description = "Look a NORAD number up by satellite name")
public class SatelliteController {

    /** What the search box shows; the rest is in the catalogue for a longer query. */
    public record SatelliteSearchResponse(List<SatelliteEntry> results, Instant catalogFetchedAt) {
    }

    /** The newest launches of a constellation, for the Starlink page. */
    public record LaunchesResponse(List<SatelliteCatalog.Launch> launches, Instant catalogFetchedAt) {
    }

    private final SatelliteCatalog catalog;

    public SatelliteController(SatelliteCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(
            summary = "Active satellites whose name matches",
            description = "Case and punctuation are ignored. Exact names first, then names starting"
                    + " with the query, then names containing it. Digits also match the NORAD number."
                    + " Names are CelesTrak's (for example HST for Hubble); only its active group is"
                    + " indexed, so debris and rocket bodies must be looked up by number.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matches (the list may be empty)"),
            @ApiResponse(responseCode = "400", description = "Query missing, too short or too long",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "503", description = "The catalogue has never been downloaded"
                    + " and no source answered; NORAD numbers still work on /api/passes",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public SatelliteSearchResponse search(
            @Parameter(description = "Part of the satellite name, or a NORAD number", example = "iss")
            @RequestParam @Size(max = 64) String q,
            @Parameter(description = "Maximum number of results", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(25) int limit) {
        SatelliteCatalog.Result result = catalog.search(q, limit);
        return new SatelliteSearchResponse(result.matches(), result.catalogFetchedAt());
    }

    @GetMapping("/launches")
    @Operation(
            summary = "Newest launches of the satellites whose name starts with the query",
            description = "Groups the active satellites whose name starts with the query (for example"
                    + " starlink) by the launch of their international designator, newest first. Each"
                    + " launch names its lowest-numbered satellite: while a launch still flies as a"
                    + " train, its passes are the group's. A satellite appears once CelesTrak catalogues"
                    + " it, usually a day or more after launch.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Launches (the list may be empty)"),
            @ApiResponse(responseCode = "400", description = "Query missing, too short or too long",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "503", description = "The catalogue has never been downloaded"
                    + " and no source answered",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public LaunchesResponse launches(
            @Parameter(description = "Start of the satellite name", example = "starlink")
            @RequestParam @Size(max = 64) String q,
            @Parameter(description = "Maximum number of launches", example = "3")
            @RequestParam(defaultValue = "3") @Min(1) @Max(10) int limit) {
        SatelliteCatalog.Launches result = catalog.latestLaunches(q, limit);
        return new LaunchesResponse(result.launches(), result.catalogFetchedAt());
    }
}
