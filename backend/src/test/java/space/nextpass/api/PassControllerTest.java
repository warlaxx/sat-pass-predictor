package space.nextpass.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import space.nextpass.domain.ObserverLocation;
import space.nextpass.passes.PassQueryService;
import space.nextpass.tle.TleNotFoundException;
import space.nextpass.tle.TleTooOldException;
import space.nextpass.tle.TleUnavailableException;
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
@org.springframework.context.annotation.Import({space.nextpass.config.TimeConfig.class,
        space.nextpass.access.AccessWebConfiguration.class})
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
                .andExpect(jsonPath("$.passes[0].track[0].illuminated").value(false))
                .andExpect(jsonPath("$.passes[0].track[0].visible").value(false))
                .andExpect(jsonPath("$.passes[0].track[1].illuminated").value(true))
                .andExpect(jsonPath("$.passes[0].track[1].visible").value(true));
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

    /**
     * The range rate is geometry and is always published; the Doppler shift needs a
     * carrier, so without one the key is present and null rather than absent — the shape
     * of the response does not depend on an optional parameter.
     */
    @Test
    void publishesTheRangeRateAndNoDopplerWithoutAFrequency() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        mockMvc.perform(get(QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequencyMhz").value(nullValue()))
                .andExpect(jsonPath("$.passes[0].aos.rangeRateKmS").value(-6.21))
                .andExpect(jsonPath("$.passes[0].los.rangeRateKmS").value(6.20))
                .andExpect(jsonPath("$.passes[0].track[0].rangeRateKmS").value(-6.21))
                .andExpect(jsonPath("$.passes[0].aos.dopplerHz").value(nullValue()))
                .andExpect(jsonPath("$.passes[0].track[0].dopplerHz").value(nullValue()));
    }

    /**
     * -f·ṙ/c at 145.8 MHz: an ISS approaching at 6.21 km/s is heard about 3 kHz high, a
     * receding one about 3 kHz low, and the culmination carries no shift at all.
     */
    @Test
    void computesTheDopplerShiftForTheRequestedFrequency() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        double expectedAos = -145.8e6 * -6.21 / 299_792.458;
        double expectedLos = -145.8e6 * 6.20 / 299_792.458;
        assertThat(expectedAos).isBetween(3_000.0, 3_100.0);

        mockMvc.perform(get(QUERY + "&frequencyMhz=145.8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequencyMhz").value(145.8))
                .andExpect(jsonPath("$.passes[0].aos.dopplerHz").value(closeTo(expectedAos, 1e-6), Double.class))
                .andExpect(jsonPath("$.passes[0].track[0].dopplerHz").value(closeTo(expectedAos, 1e-6), Double.class))
                .andExpect(jsonPath("$.passes[0].culmination.dopplerHz").value(closeTo(0.0, 1e-9), Double.class))
                .andExpect(jsonPath("$.passes[0].los.dopplerHz").value(closeTo(expectedLos, 1e-6), Double.class));
    }

    @Test
    void anImpossibleFrequencyIsRejected() throws Exception {
        mockMvc.perform(get(QUERY + "&frequencyMhz=0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(QUERY + "&frequencyMhz=300001"))
                .andExpect(status().isBadRequest());
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

    /**
     * Objects catalogued since July 2026 have six-digit numbers; {@code 100685} used to
     * be refused with a 400 before anything looked it up.
     */
    @Test
    void acceptsASixDigitNoradNumber() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        mockMvc.perform(get("/api/passes?noradId=100685&lat=48.857&lon=2.352"))
                .andExpect(status().isOk());

        verify(passQueryService).findPasses(eq(100685), any(), any(), anyDouble());
    }

    /** Z9999 in Alpha-5 is the last number a TLE line can carry. */
    @Test
    void acceptsTheLastNumberATleCanCarryAndRefusesTheNext() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        mockMvc.perform(get("/api/passes?noradId=339999&lat=48.857&lon=2.352"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/passes?noradId=340000&lat=48.857&lon=2.352"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
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
