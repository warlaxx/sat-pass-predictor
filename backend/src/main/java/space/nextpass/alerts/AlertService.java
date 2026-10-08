package space.nextpass.alerts;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import space.nextpass.domain.ObserverLocation;
import space.nextpass.domain.TleSnapshot;
import space.nextpass.passes.PassPrediction;
import space.nextpass.passes.PassQueryService;
import space.nextpass.tle.TleException;
import space.nextpass.tle.TleNotFoundException;
import space.nextpass.tle.TleStore;
import space.nextpass.weather.CloudCoverService;
import space.nextpass.weather.CloudForecast;
import space.nextpass.weather.WeatherUnavailableException;

/**
 * E-mail reminders before a pass worth going out for (ABD-42): the sign-up with its
 * confirmation, and the daily round that decides who gets a reminder.
 *
 * <h2>When a reminder goes out</h2>
 * {@code .github/workflows/pass-alerts.yml} calls {@link #dispatch()} every hour, because
 * Render's free plan sleeps and {@code @Scheduled} would not fire. Each subscription is
 * looked at once a local day, at the first round after {@code sendFromHour} in its own
 * zone, for the passes between then and noon the next day: the evening, the night and the
 * dawn. The cloud forecast is a few hours ahead at that point, which is when it is worth
 * something. A round that could not decide - no elements, no forecast - leaves the day
 * open, and the next hour tries again.
 *
 * <h2>Budget</h2>
 * Resend's free plan sends 100 e-mails a day. Every message takes one from
 * {@code alert_email_counts} first: confirmations have their own share, so that a wave of
 * sign-ups (or someone signing up strangers in a loop) cannot starve the reminders.
 */
public class AlertService {

    /** Minimum elevation of the computed passes; each subscriber's own threshold filters after. */
    static final double PREDICTION_MIN_ELEVATION_DEG = 10.0;
    static final Duration CONFIRMATION_COOLDOWN = Duration.ofMinutes(10);
    static final Duration UNCONFIRMED_KEPT = Duration.ofHours(48);
    static final String CONFIRMATION = "confirmation";
    static final String REMINDER = "reminder";

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param sendFromHour      local hour from which a subscription is looked at
     * @param maxPerEmail       subscriptions one address may hold
     * @param dailyCap          e-mails a UTC day, all kinds together
     * @param confirmationCap   of which confirmations at most
     * @param pause             between two reminders, under Resend's rate limit
     */
    public record Settings(int sendFromHour, int maxPerEmail, int dailyCap, int confirmationCap, Duration pause) {}

    /** What the page is told: the same for a new address, a known one or a confirmed one. */
    public enum SignupOutcome { PENDING, TOO_MANY, BUSY }

    /** What one round did, for the workflow's summary. */
    public record Report(int subscriptions, int due, int sent, int nothingWorthIt, int retryLater,
                         int budgetSpent, int failed, int purged) {}

    private final AlertRepository repository;
    private final Mailer mailer;
    private final AlertEmails emails;
    private final TleStore tles;
    private final PassQueryService passes;
    private final CloudCoverService clouds;
    private final Clock clock;
    private final Settings settings;

    public AlertService(AlertRepository repository, Mailer mailer, AlertEmails emails, TleStore tles,
                        PassQueryService passes, CloudCoverService clouds, Clock clock, Settings settings) {
        this.repository = repository;
        this.mailer = mailer;
        this.emails = emails;
        this.tles = tles;
        this.passes = passes;
        this.clouds = clouds;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * Records the sign-up and sends its confirmation, unless one went to this address in
     * the last {@link #CONFIRMATION_COOLDOWN}: a page reloaded, a double click, or someone
     * typing a stranger's address over and over all end in one e-mail.
     *
     * @throws TleException the satellite is unknown or its elements unavailable: nobody
     *                      signs up for passes that cannot be computed
     */
    public SignupOutcome subscribe(AlertRequest request) {
        TleSnapshot satellite = tles.get(request.noradId());
        Instant now = clock.instant();
        AlertRepository.Signup signup;
        try {
            signup = repository.subscribe(request, newToken(), settings.maxPerEmail(), now);
        } catch (DuplicateKeyException e) {
            // The same sign-up twice at the same instant: the first one sends the e-mail.
            return SignupOutcome.PENDING;
        }
        return switch (signup) {
            case AlertRepository.Signup.TooMany tooMany -> SignupOutcome.TOO_MANY;
            case AlertRepository.Signup.AlreadyConfirmed confirmed -> SignupOutcome.PENDING;
            case AlertRepository.Signup.Pending pending -> {
                Optional<Instant> last = repository.lastConfirmationTo(request.email());
                if (last.isPresent() && last.get().plus(CONFIRMATION_COOLDOWN).isAfter(now)) {
                    yield SignupOutcome.PENDING;
                }
                if (!repository.spendEmail(utcDay(now), CONFIRMATION, settings.confirmationCap(), settings.dailyCap())) {
                    yield SignupOutcome.BUSY;
                }
                try {
                    mailer.send(emails.confirmation(pending.subscription(), satellite.name()));
                } catch (Mailer.MailException e) {
                    repository.refundEmail(utcDay(now), CONFIRMATION);
                    throw e;
                }
                repository.markConfirmationSent(pending.subscription().id(), now);
                yield SignupOutcome.PENDING;
            }
        };
    }

    public Optional<AlertSubscription> confirm(String token) {
        return valid(token) ? repository.confirm(token, clock.instant()) : Optional.empty();
    }

    public boolean unsubscribe(String token) {
        return valid(token) && repository.unsubscribe(token);
    }

    /** One round: forget what nobody confirmed, then look at every subscription that is due. */
    public Report dispatch() {
        Instant now = clock.instant();
        int purged = repository.purgeUnconfirmed(now.minus(UNCONFIRMED_KEPT));
        List<AlertSubscription> all = repository.confirmed();
        Map<Integer, TleSnapshot> satellites = new HashMap<>();
        Map<Integer, TleException> missing = new HashMap<>();
        int due = 0, sent = 0, nothing = 0, retry = 0, spent = 0, failed = 0;
        for (AlertSubscription alert : all) {
            ZonedDateTime local = now.atZone(alert.timeZone());
            LocalDate today = local.toLocalDate();
            if (local.getHour() < settings.sendFromHour() || today.equals(alert.lastCheckedOn())) {
                continue;
            }
            due++;
            TleSnapshot satellite = satellites.get(alert.noradId());
            if (satellite == null && !missing.containsKey(alert.noradId())) {
                try {
                    satellite = tles.get(alert.noradId());
                    satellites.put(alert.noradId(), satellite);
                } catch (TleException e) {
                    missing.put(alert.noradId(), e);
                }
            }
            if (satellite == null) {
                if (missing.get(alert.noradId()) instanceof TleNotFoundException) {
                    // Gone from the catalogue, most likely re-entered: nothing to tell today.
                    repository.markChecked(alert.id(), today);
                    nothing++;
                } else {
                    retry++;
                }
                continue;
            }
            List<WorthSeeing.Pick> picks;
            try {
                picks = picks(alert, now, local);
            } catch (WeatherUnavailableException | TleException e) {
                retry++;
                continue;
            }
            if (picks.isEmpty()) {
                repository.markChecked(alert.id(), today);
                nothing++;
                continue;
            }
            if (!repository.spendEmail(utcDay(now), REMINDER, settings.dailyCap(), settings.dailyCap())) {
                spent++;
                continue;
            }
            try {
                mailer.send(emails.reminder(alert, satellite.name(), picks, today));
                repository.markSent(alert.id(), today);
                sent++;
                pause();
            } catch (Mailer.MailException e) {
                repository.refundEmail(utcDay(now), REMINDER);
                log.warn("Reminder {} not sent: {}", alert.id(), e.getMessage());
                failed++;
            }
        }
        return new Report(all.size(), due, sent, nothing, retry, spent, failed, purged);
    }

    private List<WorthSeeing.Pick> picks(AlertSubscription alert, Instant now, ZonedDateTime local) {
        Instant until = local.toLocalDate().plusDays(1).atTime(12, 0).atZone(alert.timeZone()).toInstant();
        ObserverLocation observer = new ObserverLocation(alert.latitudeDeg(), alert.longitudeDeg(), 0.0);
        PassPrediction prediction = passes.findPasses(alert.noradId(), observer, Duration.between(now, until),
                PREDICTION_MIN_ELEVATION_DEG);
        boolean anyVisible = prediction.passes().stream()
                .anyMatch(pass -> pass.track().stream().anyMatch(point -> point.visible()));
        // No visible pass at all: no need to spend MET Norway's budget on the sky.
        CloudForecast forecast = anyVisible
                ? clouds.forecast(alert.latitudeDeg(), alert.longitudeDeg()).forecast()
                : null;
        return anyVisible
                ? WorthSeeing.select(prediction.passes(), forecast, now, alert.minElevationDeg(), alert.maxMagnitude(),
                        alert.maxCloudPercent())
                : List.of();
    }

    private void pause() {
        if (settings.pause().isZero()) {
            return;
        }
        try {
            Thread.sleep(settings.pause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LocalDate utcDay(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC);
    }

    /** 256 bits, URL-safe: nothing to guess, and nothing to escape in a link. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean valid(String token) {
        return token != null && token.length() == 43 && token.chars().allMatch(c ->
                (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_');
    }
}
