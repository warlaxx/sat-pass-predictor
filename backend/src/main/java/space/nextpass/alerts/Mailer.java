package space.nextpass.alerts;

/** Hands an e-mail to whoever delivers it; {@link ResendMailer} in production, a fake in tests. */
public interface Mailer {

    /** @throws MailException when the message was not accepted */
    void send(Email email);

    class MailException extends RuntimeException {
        public MailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
