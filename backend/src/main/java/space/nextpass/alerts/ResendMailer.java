package space.nextpass.alerts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends through Resend's HTTP API ({@code POST /emails}), chosen on 7 October 2026 for
 * ABD-42. HTTP rather than SMTP: Render's free plan does not promise outbound SMTP ports.
 *
 * <p>The {@code Idempotency-Key} header makes a retry after a timeout safe: Resend
 * delivers a key once in 24 hours. The unsubscribe headers are RFC 8058's one-click pair,
 * which Gmail and Yahoo require of bulk senders and show as an "Unsubscribe" button.
 */
public class ResendMailer implements Mailer {

    private final RestClient resend;
    private final String from;

    /** @param resend a client with Resend's base URL and the {@code Authorization} header */
    public ResendMailer(RestClient resend, String from) {
        this.resend = resend;
        this.from = from;
    }

    @Override
    public void send(Email email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(email.to()));
        body.put("subject", email.subject());
        body.put("text", email.text());
        body.put("html", email.html());
        if (email.unsubscribeUrl() != null) {
            body.put("headers", Map.of(
                    "List-Unsubscribe", "<" + email.unsubscribeUrl() + ">",
                    "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
        }
        try {
            resend.post().uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", email.idempotencyKey())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new MailException("Resend refused or did not answer: " + e.getMessage(), e);
        }
    }
}
