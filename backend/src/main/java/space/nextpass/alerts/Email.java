package space.nextpass.alerts;

/**
 * One message, ready to hand to a {@link Mailer}.
 *
 * @param unsubscribeUrl the one-click address of RFC 8058, written into the
 *                       {@code List-Unsubscribe} header; null for a confirmation, which
 *                       subscribes nobody yet
 * @param idempotencyKey the same key for a retry of the same message, so that a timeout
 *                       followed by a second attempt delivers it once
 */
public record Email(String to, String subject, String text, String html, String unsubscribeUrl,
                    String idempotencyKey) {}
