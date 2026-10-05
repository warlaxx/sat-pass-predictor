package space.nextpass.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import space.nextpass.delays.DelayMeasurement;
import space.nextpass.images.ImageImport;
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
                    69999, 12, 3, 2, 0, 4200));

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
    class WithDelayMeasurement {
        @Autowired MockMvc mvc;
        @MockitoBean Clock clock;
        @MockitoBean GcatImporter importer;
        @MockitoBean DelayMeasurement delays;

        static final GcatImporter.Report GCAT = new GcatImporter.Report("unchanged",
                List.of(new GcatImporter.FileReport("satcat.tsv", "unchanged", 0, 0)), 0, 0, 0, 0, 0, 300);

        /** The workflow reads GCAT's fields where they always were; the delays sit beside them. */
        @Test void addsTheDelaysBesideGcatsReport() throws Exception {
            when(clock.instant()).thenReturn(Instant.parse("2026-10-03T18:23:00Z"));
            when(importer.run()).thenReturn(GCAT);
            DelayMeasurement.Stat none = new DelayMeasurement.Stat(null, null);
            DelayMeasurement.Summary empty = new DelayMeasurement.Summary(0, none, none, none);
            when(delays.run(Instant.parse("2026-10-03T18:23:00Z"))).thenReturn(new DelayMeasurement.Report(
                    "unavailable", "https://www.space-track.org unreachable for the catalogue debuts",
                    0, 0, empty, empty, 0, List.of()));

            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("unchanged"))
                    .andExpect(jsonPath("$.files[0].file").value("satcat.tsv"))
                    .andExpect(jsonPath("$.gcat").doesNotExist())
                    .andExpect(jsonPath("$.delays.status").value("unavailable"))
                    .andExpect(jsonPath("$.delays.allObjects.objects").value(0));
        }

        /** Whatever happens to the measurement, the GCAT import stands and the job passes. */
        @Test void aFailedMeasurementKeepsTheImport() throws Exception {
            when(clock.instant()).thenReturn(Instant.parse("2026-10-03T18:23:00Z"));
            when(importer.run()).thenReturn(GCAT);
            when(delays.run(any())).thenThrow(new IllegalStateException("connection refused"));

            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("unchanged"))
                    .andExpect(jsonPath("$.delays.status").value("failed"))
                    .andExpect(jsonPath("$.delays.detail").value("connection refused"));
        }
    }

    @Nested
    @WebMvcTest(controllers = IngestController.class, properties = "ingest.token=s3cret")
    class WithImages {
        @Autowired MockMvc mvc;
        @MockitoBean Clock clock;
        @MockitoBean GcatImporter importer;
        @MockitoBean ImageImport images;

        static final GcatImporter.Report GCAT = new GcatImporter.Report("unchanged",
                List.of(new GcatImporter.FileReport("satcat.tsv", "unchanged", 0, 0)), 0, 0, 0, 0, 0, 300);

        @Test void addsTheImagesBesideGcatsReport() throws Exception {
            when(importer.run()).thenReturn(GCAT);
            when(images.run()).thenReturn(new ImageImport.Report("imported", null, 1712, 3, 41_000));

            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("unchanged"))
                    .andExpect(jsonPath("$.images.status").value("imported"))
                    .andExpect(jsonPath("$.images.images").value(1712))
                    .andExpect(jsonPath("$.images.removed").value(3));
        }

        /** The database failing under the images still leaves GCAT imported and the job green. */
        @Test void aFailedImageImportKeepsTheImport() throws Exception {
            when(importer.run()).thenReturn(GCAT);
            when(images.run()).thenThrow(new IllegalStateException("connection refused"));

            mvc.perform(post("/internal/import").header("Authorization", "Bearer s3cret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("unchanged"))
                    .andExpect(jsonPath("$.images.status").value("failed"))
                    .andExpect(jsonPath("$.images.detail").value("connection refused"));
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
