package space.nextpass.alerts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import space.nextpass.tle.TleNotFoundException;

class AlertControllerTest {

    static final String FORM = """
            {"email":"ada@example.org","noradId":25544,"lat":45.7578,"lon":4.832,
             "minElevationDeg":30,"maxCloudPercent":25,"timeZone":"Europe/Paris","locale":"fr"}""";

    @Nested
    @WebMvcTest(controllers = AlertController.class, properties = "ingest.token=s3cret")
    class Enabled {
        @Autowired MockMvc mvc;
        @MockitoBean Clock clock;
        @MockitoBean AlertService alerts;

        @Test void saysItIsOpen() throws Exception {
            mvc.perform(get("/api/alerts")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        }

        @Test void aSignUpIsAcceptedWhateverTheAddressAlreadyHas() throws Exception {
            when(alerts.subscribe(any())).thenReturn(AlertService.SignupOutcome.PENDING);

            mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON).content(FORM))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value("pending"));
        }

        @Test void anInvalidFormIsABadRequestThatSaysWhy() throws Exception {
            mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON)
                            .content(FORM.replace("ada@example.org", "ada")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("email is not a valid address"));
            verifyNoInteractions(alerts);
        }

        @Test void anUnknownSatelliteIsTheUsualProblem() throws Exception {
            when(alerts.subscribe(any())).thenThrow(new TleNotFoundException(25544));

            mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON).content(FORM))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/unknown-satellite"));
        }

        @Test void limitsAndBudgetHaveTheirOwnTypes() throws Exception {
            when(alerts.subscribe(any())).thenReturn(AlertService.SignupOutcome.TOO_MANY);
            mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON).content(FORM))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/alerts-limit"));

            when(alerts.subscribe(any())).thenReturn(AlertService.SignupOutcome.BUSY);
            mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON).content(FORM))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/alerts-busy"));

            when(alerts.subscribe(any())).thenThrow(new Mailer.MailException("down", null));
            mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON).content(FORM))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test void confirmationSaysWhatWasConfirmedOrThatTheLinkIsDead() throws Exception {
            when(alerts.confirm("good")).thenReturn(Optional.of(AlertFixtures.subscription("fr")));
            when(alerts.confirm("dead")).thenReturn(Optional.empty());

            mvc.perform(post("/api/alerts/confirm").param("token", "good"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.noradId").value(25544))
                    .andExpect(jsonPath("$.email").doesNotExist());
            mvc.perform(post("/api/alerts/confirm").param("token", "dead"))
                    .andExpect(status().isNotFound());
        }

        @Test void theOneClickPostOfRfc8058Unsubscribes() throws Exception {
            mvc.perform(post("/api/alerts/unsubscribe").param("token", "abc")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
                    .andExpect(status().isNoContent());
            verify(alerts).unsubscribe("abc");
        }

        @Test void theRoundNeedsTheInternalToken() throws Exception {
            when(alerts.dispatch()).thenReturn(new AlertService.Report(3, 2, 1, 1, 0, 0, 0, 4));

            mvc.perform(post("/internal/alerts/send")).andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/alerts/send").header("Authorization", "Bearer wrong"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/alerts/send").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sent").value(1))
                    .andExpect(jsonPath("$.purged").value(4));
        }
    }

    @Nested
    @WebMvcTest(controllers = AlertController.class, properties = "ingest.token=s3cret")
    class Disabled {
        @Autowired MockMvc mvc;
        @MockitoBean Clock clock;

        @Test void everyEndpointSaysRemindersAreNotOpen() throws Exception {
            mvc.perform(get("/api/alerts")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
            mvc.perform(post("/api/alerts").contentType(MediaType.APPLICATION_JSON).content(FORM))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("https://github.com/warlaxx/sat-pass-predictor/errors/alerts-unavailable"));
            mvc.perform(post("/api/alerts/confirm").param("token", "x")).andExpect(status().isServiceUnavailable());
            mvc.perform(post("/api/alerts/unsubscribe").param("token", "x")).andExpect(status().isServiceUnavailable());
            mvc.perform(post("/internal/alerts/send").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isServiceUnavailable());
        }
    }
}
