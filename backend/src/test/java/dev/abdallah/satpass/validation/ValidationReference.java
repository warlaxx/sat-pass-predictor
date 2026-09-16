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
 * Reference de non-regression partagee entre le test Java et le script Python.
 *
 * <p>Le fichier {@value #RESOURCE} est l'unique source de verite du projet : il porte le
 * TLE, l'observateur, la fenetre et les passages attendus. Le test Java verifie
 * qu'Orekit le reproduit ; {@code scripts/validate-against-skyfield.py} verifie que
 * son contenu est physiquement correct, en le confrontant a une implementation
 * independante de SGP4.
 *
 * <p>Deux fichiers auraient signifie deux verites : on aurait pu corriger l'une en
 * laissant l'autre mentir. Ici, modifier la reference invalide immediatement les deux
 * controles.
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
                throw new IllegalStateException("reference absente du classpath : " + RESOURCE);
            }
            return new ObjectMapper().readValue(stream, ValidationReference.class);
        } catch (IOException e) {
            throw new IllegalStateException("reference illisible : " + RESOURCE, e);
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
