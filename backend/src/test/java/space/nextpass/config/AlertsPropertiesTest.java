package space.nextpass.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AlertsPropertiesTest {

    static AlertsProperties enabled(String resendUrl) {
        return new AlertsProperties(true, resendUrl, "re_test", "NextPass <alerts@nextpass.space>", null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    void defaultsToResendOverHttps() {
        assertThat(enabled(null).resendUrl()).isEqualTo("https://api.resend.com");
    }

    @Test
    void neverSendsTheKeyOverPlainHttp() {
        assertThatThrownBy(() -> enabled("http://api.resend.com")).hasMessageContaining("HTTPS");
    }

    @Test
    void onNeedsAKeyAndASender() {
        assertThatThrownBy(() -> new AlertsProperties(true, null, "", null, null, null, null, null, null, null, null,
                null, null)).hasMessageContaining("resend-api-key");
    }
}
