package dev.abdallah.satpass.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.abdallah.satpass.domain.ObserverLocation;
import dev.abdallah.satpass.passes.PassQueryService;
import dev.abdallah.satpass.tle.TleNotFoundException;
import dev.abdallah.satpass.tle.TleTooOldException;
import dev.abdallah.satpass.tle.TleUnavailableException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The web layer alone: shape of the JSON, status codes, parameter bounds.
 *
 * <p>{@link PassQueryService} is a double. Running a real propagation would make these
 * tests slow and would fail them for reasons that are none of their business — the
 * correctness of the computation is established by the milestone 2 reference.
 */
@WebMvcTest(PassController.class)
class PassControllerTest {

    private static final String QUERY =
            "/api/passes?noradId=25544&lat=45.7578&lon=4.8320&alt=170&minElevation=10&hours=240";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PassQueryService passQueryService;

    /**
     * The exit criterion of the milestone: the shape documented in
     * {@code docs/interface-mockup.html} is served as-is. The frontend was designed
     * against this JSON; any deviation here is a broken contract.
     */
    @Test
    void servesTheDocumentedJsonShape() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        mockMvc.perform(get(QUERY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.satellite.noradId").value(25544))
                .andExpect(jsonPath("$.satellite.name").value("ISS (ZARYA)"))
                .andExpect(jsonPath("$.tle.epoch").value("2026-09-15T04:12:33Z"))
                .andExpect(jsonPath("$.tle.source").value("celestrak"))
                .andExpect(jsonPath("$.tle.fetchedAt").value("2026-09-16T11:25:04Z"))
                .andExpect(jsonPath("$.observer.latitudeDeg").value(45.7578))
                .andExpect(jsonPath("$.observer.longitudeDeg").value(4.8320))
                .andExpect(jsonPath("$.observer.altitudeM").value(170.0))
                .andExpect(jsonPath("$.minElevationDeg").value(10.0))
                .andExpect(jsonPath("$.passes.length()").value(1))
                .andExpect(jsonPath("$.passes[0].aos.instant").value("2026-09-22T19:18:54Z"))
                .andExpect(jsonPath("$.passes[0].aos.azimuthDeg").value(292.5))
                .andExpect(jsonPath("$.passes[0].aos.elevationDeg").value(10.0))
                .andExpect(jsonPath("$.passes[0].aos.rangeKm").value(1553.2))
                .andExpect(jsonPath("$.passes[0].culmination.elevationDeg").value(63.1))
                .andExpect(jsonPath("$.passes[0].los.instant").value("2026-09-22T19:25:38Z"))
                .andExpect(jsonPath("$.passes[0].durationSeconds").value(404))
                .andExpect(jsonPath("$.passes[0].track.length()").value(3))
                .andExpect(jsonPath("$.passes[0].track[0].subPoint.altitudeKm").value(419.6))
                .andExpect(jsonPath("$.passes[0].track[0].illuminated").value(false));
    }

    /**
     * The age is computed by the server, from the instant of the computation and the
     * epoch of the elements. Leaving it to the client would make it depend on the
     * browser's clock, when the uncertainty banner exists precisely to state an objective
     * truth.
     */
    @Test
    void computesTheTleAgeServerSide() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        mockMvc.perform(get(QUERY))
                .andExpect(jsonPath("$.tle.ageSeconds").value(121_200))
                .andExpect(jsonPath("$.computedAt").value("2026-09-16T13:52:33Z"));
    }

    /** The three phases are read out of the track: they carry its range. */
    @Test
    void theThreePhasesCarryTheRangeFromTheTrack() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        mockMvc.perform(get(QUERY))
                .andExpect(jsonPath("$.passes[0].culmination.rangeKm").value(462.7))
                .andExpect(jsonPath("$.passes[0].culmination.instant")
                        .value("2026-09-22T19:22:16Z"))
                .andExpect(jsonPath("$.passes[0].los.rangeKm").value(1551.8));
    }

    @Test
    void appliesTheDocumentedDefaults() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        mockMvc.perform(get("/api/passes?noradId=25544&lat=45.7578&lon=4.8320"))
                .andExpect(status().isOk());

        ArgumentCaptor<Duration> window = ArgumentCaptor.forClass(Duration.class);
        ArgumentCaptor<ObserverLocation> observer = ArgumentCaptor.forClass(ObserverLocation.class);
        ArgumentCaptor<Double> minElevation = ArgumentCaptor.forClass(Double.class);
        verify(passQueryService)
                .findPasses(anyInt(), observer.capture(), window.capture(), minElevation.capture());

        assertThat(window.getValue()).isEqualTo(Duration.ofHours(48));
        assertThat(minElevation.getValue()).isEqualTo(10.0);
        assertThat(observer.getValue().altitudeMeters()).isZero();
    }

    @Test
    void unknownSatelliteIsNotFound() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenThrow(new TleNotFoundException(99999));

        mockMvc.perform(get(QUERY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("https://github.com/warlaxx/sat-pass-predictor/errors/unknown-satellite"))
                .andExpect(jsonPath("$.noradId").value(99999));
    }

    @Test
    void celestrakOutageWithNothingCachedIsServiceUnavailable() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenThrow(new TleUnavailableException("CelesTrak unreachable"));

        mockMvc.perform(get(QUERY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "15"))
                .andExpect(jsonPath("$.type")
                        .value("https://github.com/warlaxx/sat-pass-predictor/errors/tle-unavailable"));
    }

    /**
     * Same status as the outage, but a different {@code type}: a client must be able to
     * tell "CelesTrak is silent" from "the TLE we hold is too old to be of use".
     */
    @Test
    void anOverAgedTleHasItsOwnProblemType() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenThrow(new TleTooOldException(25544, Duration.ofDays(9), Duration.ofDays(7)));

        mockMvc.perform(get(QUERY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type")
                        .value("https://github.com/warlaxx/sat-pass-predictor/errors/tle-stale"));
    }

    @Test
    void aMissingRequiredParameterIsRejected() throws Exception {
        mockMvc.perform(get("/api/passes?lat=45.7578&lon=4.8320"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anImpossibleLatitudeIsRejectedBeforeAnyPropagation() throws Exception {
        mockMvc.perform(get("/api/passes?noradId=25544&lat=300&lon=4.8320"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aWindowBeyondTheCapIsRejected() throws Exception {
        mockMvc.perform(get("/api/passes?noradId=25544&lat=45.7578&lon=4.8320&hours=241"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNonNumericParameterIsRejected() throws Exception {
        mockMvc.perform(get("/api/passes?noradId=ISS&lat=45.7578&lon=4.8320"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A rejection by the framework comes out in the same format as a rejection by the
     * application.
     *
     * <p>The three tests above assert a status code and nothing else, which is exactly
     * how the endpoint went on serving two different error formats without anyone
     * noticing: {@code spring.mvc.problemdetails.enabled} defaults to {@code false} in
     * Spring Boot, so a parameter the framework rejects used to come back as a plain
     * error body with no {@code type} to branch on, while an unknown satellite came back
     * as a Problem Detail. A client cannot be asked to parse both. Asserting the media
     * type is what makes the property load-bearing instead of merely believed in.
     */
    @Test
    void aRejectionByTheFrameworkIsAlsoAProblemDetail() throws Exception {
        mockMvc.perform(get("/api/passes?noradId=25544&lat=300&lon=4.8320"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }
}
