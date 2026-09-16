package dev.abdallah.satpass.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.abdallah.satpass.domain.ObserverLocation;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.orekit.propagation.analytical.tle.TLE;
import tools.jackson.databind.ObjectMapper;

/**
 * The regression reference shared by the Java test and the Python script.
 *
 * <p>{@value #RESOURCE} is the project's single source of truth: it carries the TLE, the
 * observer, the window and the expected passes. The Java test checks that Orekit
 * reproduces it; {@code scripts/validate-against-skyfield.py} checks that its contents
 * are physically correct, by confronting it with an independent implementation of SGP4.
 *
 * <p>Two files would have meant two truths: one could have been fixed while the other
 * kept lying. Here, changing the reference invalidates both checks at once.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationReference(
        Satellite satellite,
        Observer observer,
        Window window,
        double minElevationDeg,
        List<ExpectedPass> passes) {

    public static final String RESOURCE = "/validation/iss-lyon-reference.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Satellite(String name, int noradId, String tleLine1, String tleLine2) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Observer(String name, double latitudeDeg, double longitudeDeg, double altitudeMeters) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Window(boolean startsAtTleEpoch, int hours) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedPass(
            Instant aos,
            double aosAzimuthDeg,
            Instant maxElevationTime,
            double maxElevationDeg,
            double maxElevationAzimuthDeg,
            Instant los,
            double losAzimuthDeg) {
    }

    public static ValidationReference load() {
        try (InputStream stream = ValidationReference.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("reference missing from the classpath: " + RESOURCE);
            }
            return new ObjectMapper().readValue(stream, ValidationReference.class);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable reference: " + RESOURCE, e);
        }
    }

    public TLE tle() {
        return new TLE(satellite.tleLine1(), satellite.tleLine2());
    }

    public ObserverLocation observerLocation() {
        return new ObserverLocation(
                observer.latitudeDeg(), observer.longitudeDeg(), observer.altitudeMeters());
    }

    public Duration windowDuration() {
        return Duration.ofHours(window.hours());
    }
}
