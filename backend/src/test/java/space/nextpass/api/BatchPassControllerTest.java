package space.nextpass.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import space.nextpass.access.AccessFailure;
import space.nextpass.access.AccessService;
import space.nextpass.access.AccessWebConfiguration;
import space.nextpass.config.TimeConfig;
import space.nextpass.domain.ObserverLocation;
import space.nextpass.passes.PassPrediction;
import space.nextpass.passes.PassQueryService;
import space.nextpass.tle.TleNotFoundException;
import space.nextpass.tle.TleUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The batch endpoint's web layer: its JSON, its partial failures, and — the part that
 * costs money — what admission is charged before the controller runs.
 */
@Import({TimeConfig.class, AccessWebConfiguration.class})
@WebMvcTest(controllers = {BatchPassController.class, PassController.class})
class BatchPassControllerTest {

    private static final String ERRORS = "https://github.com/warlaxx/sat-pass-predictor/errors/";
    private static final ObserverLocation PARIS = new ObserverLocation(48.8566, 2.3522, 35);
    private static final List<ObserverLocation> SITES = List.of(PassFixtures.LYON, PARIS);
    private static final String TWO_BY_TWO =
            "/v1/passes/batch?noradId=25544,99999&site=45.7578,4.8320,170&site=48.8566,2.3522,35";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PassQueryService passQueryService;

    @MockitoBean
    AccessService access;

    private static List<PassPrediction> issOverLyonAndParis() {
        PassPrediction lyon = PassFixtures.prediction();
        return List.of(lyon, new PassPrediction(lyon.tle(), PARIS, 10.0, lyon.computedAt(), lyon.passes()));
    }

    @Test
    void answersEverySatelliteForEverySiteAndReportsAMissingOneInPlace() throws Exception {
        when(passQueryService.findPassesForSites(eq(25544), eq(SITES), any(), anyDouble()))
                .thenReturn(issOverLyonAndParis());
        when(passQueryService.findPassesForSites(eq(99999), eq(SITES), any(), anyDouble()))
                .thenThrow(new TleNotFoundException(99999));

        mvc.perform(get(TWO_BY_TWO).header("X-API-Key", "key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.hours").value(48))
                .andExpect(jsonPath("$.minElevationDeg").value(10.0))
                .andExpect(jsonPath("$.track").value(true))
                .andExpect(jsonPath("$.predictions").value(4))
                .andExpect(jsonPath("$.results.length()").value(4))
                // Satellite-major order: both sites of the ISS, then both of 99999.
                .andExpect(jsonPath("$.results[0].noradId").value(25544))
                .andExpect(jsonPath("$.results[0].siteIndex").value(0))
                .andExpect(jsonPath("$.results[0].observer.latitudeDeg").value(45.7578))
                .andExpect(jsonPath("$.results[0].prediction.satellite.name").value("ISS (ZARYA)"))
                .andExpect(jsonPath("$.results[0].prediction.tle.ageSeconds").value(121_200))
                .andExpect(jsonPath("$.results[0].prediction.passes[0].culmination.elevationDeg").value(63.1))
                .andExpect(jsonPath("$.results[0].prediction.passes[0].track.length()").value(3))
                // A batch mixes satellites, so no carrier and no Doppler; the range rate stays.
                .andExpect(jsonPath("$.results[0].prediction.frequencyMhz").value(nullValue()))
                .andExpect(jsonPath("$.results[0].prediction.passes[0].track[0].rangeRateKmS").isNumber())
                .andExpect(jsonPath("$.results[0].prediction.passes[0].track[0].dopplerHz").value(nullValue()))
                .andExpect(jsonPath("$.results[0].error").value(nullValue()))
                .andExpect(jsonPath("$.results[1].siteIndex").value(1))
                .andExpect(jsonPath("$.results[1].observer.latitudeDeg").value(48.8566))
                .andExpect(jsonPath("$.results[1].prediction.observer.altitudeM").value(35.0))
                .andExpect(jsonPath("$.results[2].noradId").value(99999))
                .andExpect(jsonPath("$.results[2].siteIndex").value(0))
                .andExpect(jsonPath("$.results[2].observer.longitudeDeg").value(4.8320))
                .andExpect(jsonPath("$.results[2].prediction").value(nullValue()))
                // The same Problem Details the single endpoint returns, flattened as there.
                .andExpect(jsonPath("$.results[2].error.type").value(ERRORS + "unknown-satellite"))
                .andExpect(jsonPath("$.results[2].error.status").value(404))
                .andExpect(jsonPath("$.results[2].error.noradId").value(99999))
                .andExpect(jsonPath("$.results[3].siteIndex").value(1))
                .andExpect(jsonPath("$.results[3].error.type").value(ERRORS + "unknown-satellite"));

        verify(access).admit("key", false, 4);
    }

    @Test
    void anUnreachableSourceIsAnEntryTooNotAFailedBatch() throws Exception {
        when(passQueryService.findPassesForSites(eq(25544), any(), any(), anyDouble()))
                .thenThrow(new TleUnavailableException("CelesTrak unreachable"));

        mvc.perform(get("/v1/passes/batch?noradId=25544&site=45,4").header("X-API-Key", "key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].error.type").value(ERRORS + "tle-unavailable"))
                .andExpect(jsonPath("$.results[0].error.status").value(503));
    }

    @Test
    void trackFalseKeepsTheThreePhasesAndDropsThePolyline() throws Exception {
        when(passQueryService.findPassesForSites(anyInt(), any(), any(), anyDouble()))
                .thenReturn(List.of(PassFixtures.prediction()));

        mvc.perform(get("/v1/passes/batch?noradId=25544&site=45.7578,4.8320,170&track=false&hours=240&minElevation=20")
                        .header("X-API-Key", "key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.track").value(false))
                .andExpect(jsonPath("$.hours").value(240))
                .andExpect(jsonPath("$.results[0].prediction.passes[0].aos.instant").value("2026-09-22T19:18:54Z"))
                .andExpect(jsonPath("$.results[0].prediction.passes[0].durationSeconds").value(404))
                .andExpect(jsonPath("$.results[0].prediction.passes[0].track.length()").value(0));

        verify(passQueryService).findPassesForSites(25544, List.of(PassFixtures.LYON), Duration.ofHours(240), 20.0);
    }

    @Test
    void anInvalidBatchIsAdmittedAsOneCallThenRefusedBeforeAnyComputation() throws Exception {
        mvc.perform(get("/v1/passes/batch?noradId=1,2,3,4,5,6&site=0,0&site=1,1&site=2,2&site=3,3&site=4,4")
                        .header("X-API-Key", "key"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ERRORS + "invalid-request"))
                .andExpect(jsonPath("$.detail").value(
                        "At most 25 predictions (satellites × sites) per batch; this one asks for 30"));

        verify(access).admit("key", false, 1);
        verifyNoInteractions(passQueryService);
    }

    @Test
    void requiresAKeyAndHasNoAnonymousAlias() throws Exception {
        doThrow(AccessFailure.unauthorized()).when(access).admit(null, false, 1);

        mvc.perform(get("/v1/passes/batch?noradId=25544&site=0,0"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(ERRORS + "invalid-api-key"));
        mvc.perform(get("/api/passes/batch?noradId=25544&site=0,0"))
                .andExpect(status().isNotFound());

        verify(access).admit(null, false, 1);
        verifyNoInteractions(passQueryService);
    }

    @Test
    void aBatchBeyondTheKeysMinuteLimitSaysSoWithoutAPointlessRetryAfter() throws Exception {
        doThrow(new AccessFailure("batch-exceeds-rate-limit", 400,
                "This request computes 20 predictions; this key allows 10 per minute. Split it into smaller batches.",
                null)).when(access).admit("key", false, 20);

        mvc.perform(get("/v1/passes/batch?noradId=1,2,3,4,5,6,7,8,9,10&site=0,0&site=1,1").header("X-API-Key", "key"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.type").value(ERRORS + "batch-exceeds-rate-limit"))
                .andExpect(jsonPath("$.title").value("Batch too large for this key"));
        verifyNoInteractions(passQueryService);
    }

    @Test
    void aMonthlyQuotaRefusalNamesTheMonthlyLimit() throws Exception {
        doThrow(new AccessFailure("monthly-quota-exceeded", 429, "The monthly request quota has been reached.",
                Instant.parse("2026-10-01T00:00:00Z"))).when(access).admit("key", false, 1);

        mvc.perform(get("/v1/passes/batch?noradId=25544&site=0,0").header("X-API-Key", "key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.limit").value("monthly"))
                .andExpect(jsonPath("$.resetsAt").value("2026-10-01T00:00:00Z"));
    }
}
