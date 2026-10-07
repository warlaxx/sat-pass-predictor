package space.nextpass.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static space.nextpass.alerts.AlertFixtures.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertEmailsTest {

    final AlertEmails emails = new AlertEmails("https://www.nextpass.space/", "https://www.nextpass.space");
    final LocalDate day = LocalDate.of(2026, 10, 8);

    List<WorthSeeing.Pick> picks(Double magnitude) {
        var pass = magnitude == null
                ? pass(Instant.parse("2026-10-08T19:14:00Z"), seen(12), seen(64), seen(20))
                : pass(Instant.parse("2026-10-08T19:14:00Z"), seen(12, magnitude + 1), seen(64, magnitude), seen(20, magnitude + 1));
        return WorthSeeing.select(List.of(pass), clouds(10), EVENING, 30, null, 25);
    }

    @Test
    void theConfirmationLinksToThePageNotToTheApi() {
        Email email = emails.confirmation(subscription("en"), "ISS (ZARYA)");

        assertThat(email.to()).isEqualTo("ada@example.org");
        assertThat(email.text()).contains("https://www.nextpass.space/alerts/confirm?token=tok_tok_")
                .contains("ISS (ZARYA)").contains("45.76, 4.84").contains("48 hours");
        assertThat(email.unsubscribeUrl()).isNull();
        assertThat(email.html()).contains("href=\"https://www.nextpass.space/alerts/confirm?token=");
    }

    @Test
    void theReminderGivesLocalTimesDirectionAndCloudsInTheReadersLanguage() {
        Email en = emails.reminder(subscription("en"), "ISS (ZARYA)", picks(null), day);
        Email fr = emails.reminder(subscription("fr"), "ISS (ZARYA)", picks(null), day);

        // 19:14 UTC is 21:14 in Paris in October.
        assertThat(en.subject()).isEqualTo("ISS (ZARYA): visible at 21:14, clear sky");
        assertThat(en.text()).contains("Thu 8, 21:14 → 21:16: up to 64° towards").contains("clouds 10%")
                .contains("brightness is not known").contains("MET Norway (CC BY 4.0)")
                .contains("https://www.nextpass.space/?norad=25544&lat=45.76&lon=4.84")
                .contains("https://www.nextpass.space/alerts/unsubscribe?token=");
        assertThat(fr.subject()).isEqualTo("ISS (ZARYA) : visible à 21:14, ciel dégagé");
        assertThat(fr.text()).contains("jusqu'à 64° vers le").contains("nuages 10 %")
                .contains("https://www.nextpass.space/fr/?norad=25544")
                .contains("https://www.nextpass.space/fr/alerts/unsubscribe?token=");
    }

    @Test
    void theOneClickUnsubscribeGoesToTheApiAndTheKeyIsOnePerDay() {
        Email email = emails.reminder(subscription("en"), "ISS", picks(null), day);

        assertThat(email.unsubscribeUrl()).isEqualTo("https://www.nextpass.space/api/alerts/unsubscribe?token="
                + subscription("en").token());
        assertThat(email.idempotencyKey()).isEqualTo("reminder-7-2026-10-08");
    }

    @Test
    void aKnownMagnitudeIsWrittenWithATrueMinusSign() {
        Email en = emails.reminder(subscription("en"), "ISS", picks(-3.1), day);
        Email fr = emails.reminder(subscription("fr"), "ISS", picks(-3.1), day);

        assertThat(en.text()).contains("magnitude −3.1").doesNotContain("brightness is not known");
        assertThat(fr.text()).contains("magnitude −3,1");
    }

    @Test
    void aNameIsEscapedInTheHtml() {
        Email email = emails.reminder(subscription("en"), "<b>X&Y</b>", picks(null), day);

        assertThat(email.html()).contains("&lt;b&gt;X&amp;Y&lt;/b&gt;").doesNotContain("<b>X");
    }

    @Test
    void sixteenCompassPointsInBothLanguages() {
        assertThat(AlertEmails.compass(0, false)).isEqualTo("N");
        assertThat(AlertEmails.compass(225, false)).isEqualTo("SW");
        assertThat(AlertEmails.compass(225, true)).isEqualTo("SO");
        assertThat(AlertEmails.compass(359, true)).isEqualTo("N");
    }
}
