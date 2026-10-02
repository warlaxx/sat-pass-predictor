package space.nextpass.ingest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import space.nextpass.gcat.GcatImportException;
import space.nextpass.gcat.GcatImporter;

class IngestControllerTest {

    // The controller must sit under the application's package: before this test it lived
    // in a package the component scan never reached, and production answered 404.
    @Nested
    @WebMvcTest(controllers = IngestController.class, properties = "ingest.token=s3cret")
    class Configured {
        @Autowired MockMvc mvc;
        @MockitoBean Clock clock;
        @MockitoBean GcatImporter importer;

        @Test void rightTokenRunsTheImport() throws Exception {
            when(importer.run()).thenReturn(new GcatImporter.Report("imported",
                    List.of(new GcatImporter.FileReport("satcat.tsv", "imported", 69999, 0)),
                    69999, 12, 3, 2, 4200));

            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value("imported"))
                    .andExpect(jsonPath("$.files[0].file").value("satcat.tsv"))
                    .andExpect(jsonPath("$.newObjects").value(12))
                    .andExpect(jsonPath("$.newSeparations").value(2));
        }

        @Test void missingOrWrongTokenIsRefused() throws Exception {
            mvc.perform(post("/internal/import"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cre"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/import").header("Authorization", "s3cret"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(importer);
        }

        @Test void aSecondCallWhileOneRunsIsAConflict() throws Exception {
            when(importer.run()).thenThrow(new GcatImporter.AlreadyRunningException());

            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isConflict());
        }

        @Test void anUnreachableGcatIsABadGatewaySoTheJobFails() throws Exception {
            when(importer.run()).thenThrow(new GcatImportException("GCAT answered 503 for satcat.tsv"));

            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.detail").value("GCAT answered 503 for satcat.tsv"));
        }
    }

    @Nested
    @WebMvcTest(controllers = IngestController.class, properties = "ingest.token=s3cret")
    class WithoutDatabase {
        @Autowired MockMvc mvc;
        @MockitoBean Clock clock;

        @Test void saysThereIsNothingToImportInto() throws Exception {
            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("database")));
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
