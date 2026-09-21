# Flat subscription billing

Milestone 14 adds Stripe-hosted Checkout and Customer Portal to `/account/`.
Billing is disabled by default. No Stripe secret goes to the browser.

## Plans and quotas

| Plan | Monthly price | Calls / UTC calendar month | Calls / minute |
| --- | --- | --- | --- |
| Free | €0 | 1,000 | 10 |
| Hobby | €9 | 25,000 | 60 |
| Pro | €49 | 250,000 | 120 |

Each self-serve account has one key in this release. Multi-key Pro and a custom
Business offer remain pricing hypotheses; they are not sold by this implementation.
The anonymous demo retains its shared 200/day quota; operator-issued `standard`
keys retain their existing daily limits. V4 migrates existing self-serve keys to
Free monthly quotas without deleting usage or reactivating revoked credentials.
Monthly quotas reset at the start of the UTC calendar month, **not** on the Stripe
renewal date. This is displayed on the dashboard. No usage is reported to Stripe:
exhaustion returns `429` with `Retry-After`, never an overage invoice. Upgrades,
downgrades and key rotation preserve counters, so a downgrade can immediately
exhaust the smaller quota. Revoke disables a credential, not its subscription;
use the Portal to cancel billing.

## Activate in a Stripe sandbox first

1. Enable PostgreSQL and [self-serve accounts](self-serve-accounts.md).
2. In Stripe, create two **flat, recurring monthly EUR prices**, €9 for Hobby and
   €49 for Pro. Quantity must be one; do not use metered or tiered prices.
3. Enable Customer Portal with these two prices for subscription updates and
   cancellation. Choose the desired proration/cancellation policy in Stripe.
   Configure cancellation at period end if paid access should continue until then.
4. Set backend secrets/configuration:

   ```text
   BILLING_ENABLED=true
   STRIPE_SECRET_KEY=sk_test_...
   STRIPE_WEBHOOK_SECRET=whsec_...
   STRIPE_HOBBY_PRICE=price_...
   STRIPE_PRO_PRICE=price_...
   ```

   Return URLs are built from the existing `ACCOUNT_BASE_URL`, never from request
   input. Do not change price IDs while subscriptions still use the old prices:
   unmapped active prices intentionally fail reconciliation and return 503.
5. Register `https://YOUR_BACKEND/billing/webhook` for snapshot events:
   `customer.subscription.created`, `customer.subscription.updated`,
   `customer.subscription.deleted`, `checkout.session.completed`,
   `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`.
   Use the API version pinned by stripe-java 33.4.2 (see `Stripe.API_VERSION`).
   For local testing, `stripe listen --forward-to localhost:8080/billing/webhook`
   prints a separate signing secret; use it only for that listener.
6. Sign in, choose Hobby, complete a sandbox payment, then refresh the dashboard.
   Verify the plan/quota and a real keyed API call. Change to Pro via the Portal,
   cancel, test a failed payment and replay a webhook. Close Checkout's tab after
   paying to verify that access does not depend on the success redirect.
7. Repeat configuration with live prices, keys and the live endpoint secret only
   after sandbox acceptance and the milestone 15 commercial release prerequisites.

This PR neither provisions Stripe resources nor charges a real customer. Automated
checks exercise Stripe HTTP fixtures, signed payloads and real local PostgreSQL;
the live provider acceptance flow remains a deployment step.

## Consistency and recovery

Only a signed webhook updates entitlements. Signature verification uses the raw
request body and the SDK's timestamp tolerance. Checkout/Portal endpoints require
the GitHub session and CSRF; the webhook requires no browser session.

Processing locks the account and reads its **current** Stripe subscriptions, so
late events and manual replays cannot restore stale access. Active/trialing grants
its configured plan; past_due, unpaid, incomplete and paused receive Free. A
scheduled cancellation retains access while the subscription remains active.
Other live subscriptions still block a second Checkout, including delinquent ones:
the customer is directed to the Portal. Multiple subscriptions are reconciled to
the highest mapped active tier. Unknown active prices or non-unit quantities fail
closed without recording a successful receipt.

Event ID receipt and plan/quota updates commit in one PostgreSQL transaction.
Failures roll back and return 503 for Stripe retries. A repeated successful event
is a no-op. Only the event ID and processing time are retained, not payment data.
Events for unknown customers are acknowledged without modifying any account.
Customer IDs are established server-side before Checkout is exposed to the user.

Checkout writes use stable Stripe idempotency keys. Concurrent clicks serialize
on the account lock and reuse an open session (including its original plan).
Expired sessions can be replaced; a finished, canceled subscription can subscribe
again. Stripe HTTP requests have finite connection/read timeouts and database
transactions have a 45-second timeout. No remote billing calls occur in prediction
admission. Keep processed event IDs; deleting them is not necessary for replay.

For a failed delivery, fix the cause, then use Stripe Dashboard **Resend**, or:

```sh
stripe events resend evt_EXAMPLE --webhook-endpoint=we_EXAMPLE
```

Resend creates a fresh signature. Do not replay an old captured signature or add
an unsigned production replay endpoint. Failed event IDs were rolled back, so the
same event can succeed after repair. Successful duplicates need no reapplication:
new subscription changes arrive as new event IDs. Inspect failed deliveries in
Stripe Workbench; this release has no independent reconciliation scheduler.

References: [Checkout](https://docs.stripe.com/api/checkout/sessions/create),
[Customer Portal](https://docs.stripe.com/api/customer_portal/sessions/create),
[webhook signatures, retries and ordering](https://docs.stripe.com/webhooks).
