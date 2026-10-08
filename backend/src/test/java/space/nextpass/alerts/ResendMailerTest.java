package space.nextpass.alerts;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** What reaches Resend, without Resend. */
class ResendMailerTest {

    MockRestServiceServer server;
    ResendMailer mailer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://resend.test")
                .defaultHeader("Authorization", "Bearer re_test");
        server = MockRestServiceServer.bindTo(builder).build();
        mailer = new ResendMailer(builder.build(), "NextPass <alerts@nextpass.space>");
    }

    @Test
    void postsTheMessageWithTheOneClickHeadersAndAnIdempotencyKey() {
        server.expect(requestTo("https://resend.test/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test"))
                .andExpect(header("Idempotency-Key", "reminder-7-2026-10-08"))
                .andExpect(jsonPath("$.from").value("NextPass <alerts@nextpass.space>"))
                .andExpect(jsonPath("$.to[0]").value("ada@example.org"))
                .andExpect(jsonPath("$.subject").value("ISS tonight"))
                .andExpect(jsonPath("$.headers['List-Unsubscribe']").value("<https://x.test/u?token=t>"))
                .andExpect(jsonPath("$.headers['List-Unsubscribe-Post']").value("List-Unsubscribe=One-Click"))
                .andRespond(withSuccess("{\"id\":\"49a3999c\"}", org.springframework.http.MediaType.APPLICATION_JSON));

        mailer.send(new Email("ada@example.org", "ISS tonight", "text", "<p>html</p>", "https://x.test/u?token=t",
                "reminder-7-2026-10-08"));

        server.verify();
    }

    @Test
    void aConfirmationCarriesNoUnsubscribeHeader() {
        server.expect(requestTo("https://resend.test/emails"))
                .andExpect(jsonPath("$.headers").doesNotExist())
                .andRespond(withSuccess());

        mailer.send(new Email("ada@example.org", "Confirm", "text", "<p>html</p>", null, "confirm-7-abc"));

        server.verify();
    }

    @Test
    void aRefusalIsAMailException() {
        server.expect(requestTo("https://resend.test/emails")).andRespond(withStatus(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> mailer.send(new Email("a@b.co", "s", "t", "h", null, "k")))
                .isInstanceOf(Mailer.MailException.class);
    }
}
