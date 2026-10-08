package space.nextpass.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import space.nextpass.config.SeparationsProperties;
import space.nextpass.separations.SeparationFeed;
import space.nextpass.separations.SeparationRepository;
import space.nextpass.separations.Separations;

/**
 * What separated in orbit: the list and one event, for the separation pages (phase 3.1
 * of the roadmap), and the RSS feed of the newest (phase 3.4, ABD-15).
 *
 * <p>Not a {@link PassController}: neither the API key nor the quota applies. The answer
 * changes once a night, with the import, so it is cached publicly for a quarter of an
 * hour; the passes an event page leads to are still metered where they always were.
 *
 * <p>The events live in the database. An instance without one answers 503 with its own
 * type, rather than an empty list that would read as "nothing happened in orbit".
 */
@RestController
@EnableConfigurationProperties(SeparationsProperties.class)
@RequestMapping("/api/separations")
@Tag(name = "Separations", description = "Objects released by other objects in orbit")
public class SeparationController {

    private static final String TYPE_PREFIX = "https://github.com/warlaxx/sat-pass-predictor/errors/";

    /** A record identifier: one letter and digits (S100685). */
    private static final Pattern ID = Pattern.compile("[A-Z]\\d{1,8}");

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(15)).cachePublic();

    private static final MediaType RSS = new MediaType("application", "rss+xml", StandardCharsets.UTF_8);

    private final ObjectProvider<SeparationRepository> repository;
    private final Clock clock;
    private final SeparationFeed feed;

    public SeparationController(ObjectProvider<SeparationRepository> repository, Clock clock,
                                SeparationsProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.feed = new SeparationFeed(properties.siteUrl());
    }

    @GetMapping
    @Operation(summary = "The newest separation events",
            description = "An event is one parent releasing one or more objects at the same recorded"
                    + " moment. A release leaves at least one object that is not debris; a"
                    + " fragmentation leaves only debris. Dates carry the precision they were recorded"
                    + " with. Also returns this year's events by month.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Events, newest first"),
            @ApiResponse(responseCode = "400", description = "Unknown kind or limit out of range",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "503", description = "This instance has no database",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public ResponseEntity<?> latest(
            @Parameter(description = "release, fragmentation, or all", example = "all")
            @RequestParam(defaultValue = "all") String kind,
            @Parameter(description = "Maximum number of events", example = "50")
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        SeparationRepository separations = repository.getIfAvailable();
        if (separations == null) {
            return unavailable();
        }
        Separations.Kind filter = switch (kind.toLowerCase(Locale.ROOT)) {
            case "all" -> null;
            case "release" -> Separations.Kind.RELEASE;
            case "fragmentation" -> Separations.Kind.FRAGMENTATION;
            default -> throw new IllegalArgumentException("kind must be release, fragmentation or all");
        };
        var body = new Separations.ListResponse(
                separations.latest(filter, limit), separations.stats(clock.instant()), separations.updatedAt());
        return ResponseEntity.ok().cacheControl(CACHE).body(body);
    }

    /**
     * No {@code produces}: the content type is set on the answer instead, so that a reader
     * asking only for {@code text/xml} or {@code application/xml} still gets the feed
     * rather than a 406. The literal path wins over {@code /{id}}.
     */
    @GetMapping("/feed.xml")
    @Operation(summary = "The newest separation events, as an RSS 2.0 feed",
            description = "In English. One item per event, linking to its page, newest first by the"
                    + " time NextPass learnt of it, not by separation date: a separation recorded"
                    + " years ago and catalogued last night comes first. At most 50 items.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "RSS 2.0, newest first",
                    content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/rss+xml",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string"))),
            @ApiResponse(responseCode = "503", description = "This instance has no database",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public ResponseEntity<?> feed() {
        SeparationRepository separations = repository.getIfAvailable();
        if (separations == null) {
            return unavailable();
        }
        byte[] body = feed.write(separations.newest(SeparationFeed.ENTRIES), separations.updatedAt());
        return ResponseEntity.ok().cacheControl(CACHE).contentType(RSS).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One separation event",
            description = "The event a record belongs to, found from any of its members: parent, the"
                    + " parent's own parent, the children (at most 200, childCount says how many) and"
                    + " the recorded fields of each.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The event"),
            @ApiResponse(responseCode = "404", description = "No separation has this record",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "503", description = "This instance has no database",
                    content = @io.swagger.v3.oas.annotations.media.Content)})
    public ResponseEntity<?> event(
            @Parameter(description = "Identifier of any object of the event", example = "S100685")
            @PathVariable String id) {
        SeparationRepository separations = repository.getIfAvailable();
        if (separations == null) {
            return unavailable();
        }
        String normalised = id.toUpperCase(Locale.ROOT);
        var event = ID.matcher(normalised).matches() ? separations.event(normalised) : java.util.Optional.<Separations.Event>empty();
        return event.<ResponseEntity<?>>map(found -> ResponseEntity.ok().cacheControl(CACHE).body(found))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "separation-not-found", "Separation not found",
                        "No separation is recorded for " + id + "."));
    }

    private static ResponseEntity<ProblemDetail> unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "separations-unavailable", "Separations unavailable",
                "This instance has no database, so it holds no separation events.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + slug));
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
