# Billing — milestone 14

Implemented, disabled by default. This is an integration ready for Stripe sandbox
verification, not evidence of a live payment or authorization to launch commercially.
Accounts, database access and a created self-serve key are prerequisites.

## Offer and quota contract

| Plan | Requests | Per minute | Keys |
| --- | --- | --- | --- |
| Free preview | 100 / UTC day | 10 | 1 |
| Hobby | 25,000 / UTC calendar month | 30 | 1 |
| Pro | 250,000 / UTC calendar month | 120 | 1 |

Paid quotas reset at 00:00 UTC on the first day of each month, **not** on the
subscription anniversary. The first partial calendar month has the full quota;
upgrades/downgrades, revocation and key rotation never reset usage. Free calls
already made in that month count after upgrading. A downgrade below current usage
returns 429 until the relevant reset. Both API URL aliases and cached answers count.
The public demo and operator-issued keys keep their existing daily limits.

The €9/€49 prices in the roadmap remain hypotheses. Prices and taxes are shown by
Stripe before confirmation, using operator-configured recurring monthly Price IDs.
There is no metered overage. Pro currently has one key, not the roadmap's proposed
multi-key offer; Business and the proposed 1,000/month free offer are not implemented.

## Configure a sandbox first

Keep `BILLING_ENABLED=false` on the public deployment until the release gates pass.
In a dedicated test environment, enable accounts per [the account runbook](self-serve-accounts.md), then set:

```text
BILLING_ENABLED=true
STRIPE_SECRET_KEY=sk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...
STRIPE_HOBBY_PRICE=price_...
STRIPE_PRO_PRICE=price_...
```

Create two distinct fixed-price, **monthly, quantity-one** recurring prices in the
same Stripe sandbox. Do not configure trials, annual prices, adjustable quantities
or additional subscription items. Automatic Tax is enabled in Checkout; finish the
Stripe Tax configuration for the intended test regions before exercising Checkout.

Configure the Customer Portal in that sandbox: payment-method updates,
cancellation at period end, and changes between exactly those two prices. Configure
immediate plan changes to invoice immediately and collect payment; do not configure
an upgrade that leaves the old paid invoice as the latest invoice. Pending or
failed invoices receive free-preview limits until payment is confirmed. The portal
configuration and actual prices are operator settings, not provisioned by this code.

Register a **snapshot** webhook at `https://YOUR_BACKEND/billing/webhook` for:

- `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`
- `invoice.paid`, `invoice.payment_failed`
- `checkout.session.completed`

Use the API version pinned by stripe-java 33.3.0 for the webhook destination.
For local forwarding, run `stripe listen --forward-to localhost:8080/billing/webhook`
and use its signing secret. Keep production and test secrets separate and out of Git.
The SDK supplies its pinned API version for outgoing requests. Request timeouts are
bounded and automatic network retries are disabled; Stripe retries failed webhooks.

## Lifecycle and failure handling

The backend creates Checkout and Portal sessions using the authenticated GitHub
account's stored customer ID. No customer ID, key ID, arbitrary price or return URL
is accepted from the browser. Account POSTs retain session authentication and CSRF.
An open Checkout is reused for the same plan; a different choice is rejected until
it expires. Existing subscriptions must be changed through the Portal. Customer
and Checkout creation use idempotency keys, and account row locks serialize calls.

The Checkout return only returns to the dashboard. **Only verified webhooks apply
paid rights.** Signature verification uses the raw body and a five-minute tolerance.
On receipt, an account row is locked, current subscriptions are read from Stripe,
and the key's entitlement and processed event ID are committed together. Repeated
event IDs are no-ops; old events arriving later read current state instead of
reapplying their old payload. Concurrent handlers across instances serialize.
Provider/database failure rolls back the event marker and returns 503 for retry.
Events for customers outside this application are ignored.

Only one active subscription with one recognized price, quantity one and a paid
latest invoice grants access. Unknown prices, trial, unpaid, past-due, paused and
incomplete subscriptions do not. Multiple ongoing subscriptions fail reconciliation
for operator investigation rather than selecting one silently. Subscription
cancellation at period end retains access until the period ends; immediate
cancellation removes it. Webhooks never reactivate a revoked API credential.

Entitlements expire at Stripe's current subscription-item period end even if a
renewal webhook is lost: admission then uses free-preview limits. A delayed renewal
can therefore temporarily reduce access. Monitor webhook delivery and reconcile
failures before promising availability. Stripe I/O occurs under an account lock
(maximum transaction timeout 30 s); API admission locks only the key and does not
make provider calls. This synchronous approach is for the initial low-volume service.

## Replay and release verification

For a failed delivery, use **Resend** in Stripe Workbench or:

```sh
stripe events resend evt_EXAMPLE --webhook-endpoint=we_EXAMPLE
```

Stripe signs the delivery again. Do not replay captured HTTP signatures after their
time window. Failed events have no committed marker and can succeed on retry;
successful duplicates do not reapply changes. To repair later provider changes,
resend a relevant event not yet processed or trigger a subscription update in Stripe.
Do not delete quota rows or event markers as a routine recovery step. An ambiguous
customer-creation failure unresolved beyond Stripe's idempotency retention window
requires checking Stripe for orphan customers before retrying.

Before activating billing publicly, run a real **sandbox** lifecycle: GitHub login,
key creation, Checkout payment, webhook receipt, paid API calls, repeated delivery,
Hobby → Pro → Hobby through Portal, failed renewal and recovery, cancellation at
period end and immediate cancellation, re-subscription, and key rotation/revocation.
Verify actual portal proration behavior, tax display, database state and dashboard
quotas. Local tests cannot establish these provider-side settings.

Automated coverage uses real PostgreSQL for migrations, atomic event application,
duplicates, concurrent handlers/admission, rollback/retry, quota periods and key
rotation; loopback HTTP for real Stripe SDK requests and subscription parsing;
MockMvc for session/CSRF/signature checks; and DOM tests for quota display and billing
buttons. Normal Maven `verify` with `TEST_DATABASE_*` and the existing dashboard
Node test command run these in CI.

Follow [commercial readiness](commercial-readiness.md) before accepting real money.
Primary integration references: [Stripe webhooks](https://docs.stripe.com/webhooks),
[Checkout creation](https://docs.stripe.com/api/checkout/sessions/create), and
[Customer Portal](https://docs.stripe.com/api/customer_portal/sessions/create).
