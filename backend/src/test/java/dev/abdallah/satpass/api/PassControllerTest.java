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
 * La couche web seule : forme du JSON, codes HTTP, bornes des parametres.
 *
 * <p>{@link PassQueryService} est double. Faire tourner une propagation reelle rendrait
 * ces tests lents et les ferait echouer pour des raisons qui ne les regardent pas — la
 * justesse du calcul est etablie par la reference du jalon 2.
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
     * Le critere de sortie du jalon : la forme documentee dans
     * {@code docs/maquette-interface.html} est servie telle quelle. Le frontend a ete
     * dessine contre ce JSON ; tout ecart ici est une rupture de contrat.
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
     * L'age est calcule par le serveur, depuis l'instant du calcul et l'epoque des
     * elements. Le laisser au client le rendrait dependant de l'horloge du navigateur,
     * alors que le bandeau d'incertitude est justement la pour dire une verite objective.
     */
    @Test
    void computesTheTleAgeServerSide() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenReturn(PassFixtures.prediction());

        mockMvc.perform(get(QUERY))
                .andExpect(jsonPath("$.tle.ageSeconds").value(121_200))
                .andExpect(jsonPath("$.computedAt").value("2026-09-16T13:52:33Z"));
    }

    /** Les trois phases sont prelevees dans la trajectoire : elles portent sa distance. */
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
                        .value("https://github.com/warlaxx/sat-pass-predictor/errors/satellite-inconnu"))
                .andExpect(jsonPath("$.noradId").value(99999));
    }

    @Test
    void celestrakOutageWithNothingCachedIsServiceUnavailable() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenThrow(new TleUnavailableException("CelesTrak injoignable"));

        mockMvc.perform(get(QUERY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.type")
                        .value("https://github.com/warlaxx/sat-pass-predictor/errors/tle-indisponible"));
    }

    /**
     * Meme code que la panne, mais un {@code type} different : un client doit pouvoir
     * distinguer « CelesTrak est muet » de « le TLE qu'on a est trop vieux pour servir ».
     */
    @Test
    void anOverAgedTleHasItsOwnProblemType() throws Exception {
        when(passQueryService.findPasses(anyInt(), any(), any(), anyDouble()))
                .thenThrow(new TleTooOldException(25544, Duration.ofDays(9), Duration.ofDays(7)));

        mockMvc.perform(get(QUERY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type")
                        .value("https://github.com/warlaxx/sat-pass-predictor/errors/tle-perime"));
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
}
