# E-mail reminders (ABD-42)

An e-mail on the days a pass is worth going out for, and only then. Asked for on Reddit on
7 October 2026 ("tell me only if the ISS is going to blaze across a clear sky"); push
notifications (ABD-52) and separated objects (ABD-53) are separate issues.

## What "worth it" means

`WorthSeeing` decides, with no clock, database or network, so that `WorthSeeingTest` reads
as the rule. A pass qualifies when all of these hold:

| Criterion | Default | Why |
|---|---|---|
| Some of it is potentially visible: satellite sunlit, observer's Sun at −6° or lower | — | The same flag as the pass tables and the `.ics` file |
| The highest **visible** sample is at least the subscriber's elevation | 30° | A pass peaking at 70° in shadow and seen up to 15° is a 15° pass to the eye |
| Its brightest magnitude is at most the subscriber's, **when known** | any | Only the ISS has a standard magnitude (`Brightness`); the e-mail says when it is unknown |
| MET Norway's cloud cover at the highest visible sample is at most the subscriber's | 25 % | The pass tables' "clear". No forecast for that hour, no e-mail |

## When it is sent

`.github/workflows/pass-alerts.yml` calls `POST /internal/alerts/send` at :17 every hour
(same `IMPORT_TOKEN` as the nightly import; Render's free plan sleeps, so no `@Scheduled`).
Each confirmed subscription is looked at **once a local day**, at the first round after
**15:00 in its own time zone**, for the passes between then and noon the next day. A round
that could not decide (no elements, MET's per-minute budget spent) leaves the day open and
the next hour tries again. At most one reminder a day, listing up to three passes.

## Budget

Resend's free plan: **100 e-mails a day, 3 000 a month**. `alert_email_counts` keeps the
day under `alerts.daily-cap` (95), of which confirmations take at most
`alerts.confirmation-cap` (30), so that a wave of sign-ups, or someone typing strangers'
addresses in a loop, cannot starve the reminders. A reminder over budget waits for the
next round and is reported as `budgetSpent`. The monthly 3 000 is not counted: 95 a day
fits within it (2 945 in a 31-day month), but any other mail sent from the same Resend
account draws on the same allowance, so watch the `sent` figure of the workflow's summary
and move to a paid plan if the account sends anything else.

Abuse limits: five confirmed subscriptions per address (unconfirmed ones do not count, and
past five the oldest unconfirmed one makes room, so nobody can lock an address out by
typing it), one confirmation e-mail per address per ten
minutes, a confirmed subscription that a second sign-up never changes, and the same `202`
whether the address is new, waiting or confirmed (the answer tells a stranger nothing).

## Links and mail scanners

Mail scanners open every link of a message, and some run its script. So the confirmation
and unsubscribe links lead to pages (`/alerts/confirm`, `/alerts/unsubscribe`, rendered in
the browser, never prerendered nor in the sitemap) where a **button** sends the `POST`.
The `List-Unsubscribe` / `List-Unsubscribe-Post` headers (RFC 8058, required by Gmail and
Yahoo for bulk senders) point at `POST /api/alerts/unsubscribe` directly: that one is a
POST by definition.

## Data

`pass_alerts` (`V10__pass_alerts.sql`): the address, the satellite, the place rounded to
0.01° (about a kilometre), the thresholds, the IANA zone and language, the dates of the last
look and the last reminder, and a 256-bit token stored as is (each reminder writes it back
into its links). Unconfirmed sign-ups are deleted after 48 hours by the hourly round;
unsubscribing deletes the row. The privacy policy (`/legal#privacy`) says the same.

## Turning it on

1. **Resend**: create an account, add and verify the sending domain (`nextpass.space`:
   the SPF, DKIM and, ideally, DMARC records Resend lists), create an API key with
   "sending access" only.
2. **Render**: `ALERTS_ENABLED=true`, `RESEND_API_KEY`, `ALERTS_FROM`
   (`NextPass <alerts@nextpass.space>`); `API_ACCESS_ENABLED=true` and its database are
   already required by the import. The migration runs at startup.
3. **GitHub**: nothing new; the workflow reuses `BACKEND_URL` and `IMPORT_TOKEN`. Until
   step 2 it answers 503 and the job ends with a warning, not a failure.
4. **Check**: sign up on `/alerts` with your own address, confirm, then run the workflow
   by hand (Actions → Pass reminders → Run workflow) after 15:00 your time on a day with
   a visible ISS pass and a clear forecast. The summary shows `sent: 1`; check the e-mail
   is not in spam, then the "Unsubscribe" button of your mail client.

Until step 4 has been done once, the feature is **implemented, not verified**: no real
e-mail has been sent by this code.
