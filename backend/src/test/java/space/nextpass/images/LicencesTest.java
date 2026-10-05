package space.nextpass.images;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LicencesTest {

    @ParameterizedTest
    @ValueSource(strings = {"pd", "pd-usgov-nasa", "cc0", "cc-by-2.0", "cc-by-sa-4.0", "CC-BY-SA-3.0",
            "cc-by-sa-3.0-migrated"})
    void freeLicencesAreKept(String code) {
        assertThat(Licences.accepts(code)).isTrue();
    }

    /** NonCommercial, NoDerivatives, GFDL alone and anything unknown are refused. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"cc-by-nc-2.0", "cc-by-nc-sa-4.0", "cc-by-nd-4.0", "gfdl", "fal", "attribution",
            "copyrighted"})
    void everythingElseIsRefused(String code) {
        assertThat(Licences.accepts(code)).isFalse();
    }
}
