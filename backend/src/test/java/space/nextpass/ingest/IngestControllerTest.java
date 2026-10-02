package space.nextpass.ingest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

class IngestControllerTest {

    // The controller must sit under the application's package: before this test it lived
    // in a package the component scan never reached, and production answered 404.
    @Nested
    @WebMvcTest(controllers = IngestController.class, properties = "ingest.token=s3cret")
    class Configured {
        @Autowired MockMvc mvc;
        @MockitoBean Clock clock;

        @Test void rightTokenRunsTheImport() throws Exception {
            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("noop"));
        }

        @Test void missingOrWrongTokenIsRefused() throws Exception {
            mvc.perform(post("/internal/import"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cre"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/import").header("Authorization", "s3cret"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @WebMvcTest(controllers = IngestController.class, properties = "ingest.token=")
    class NotConfigured {
        @Autowired MockMvc mvc;
        @MockitoBean Clock clock;

        @Test void failsClosedWithoutAToken() throws Exception {
            mvc.perform(post("/internal/import").header("Authorization", "Bearer "))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.title").value("Import"));
        }
    }
}
