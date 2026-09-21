# Commercial readiness — 21 September 2026

Baseline reviewed: `main` / `origin/main` at `782646c`. The public prediction product,
scientific regression suite, API-key quota infrastructure and cache are implemented.
Milestones 13–14 implement self-serve accounts and opt-in Stripe billing. Neither
a production GitHub login nor a real Stripe sandbox lifecycle is established by
local tests. The application has not been launched as a paid service.

## Remaining work, in order

| Gate | Work | Evidence required |
| --- | --- | --- |
| Activate accounts | Provision/confirm PostgreSQL, GitHub OAuth App, exact backend origin and secure cookies; deploy | Real login → key → API call → rotation → rejection of old key → revocation → logout |
| 14 · Activate billing | Configure sandbox prices, Tax, Portal and webhook endpoint; exercise the implemented Checkout/Portal integration | Real sandbox payment, renewal failure/recovery, plan changes/proration, cancellation and re-subscription; see [runbook](billing.md) |
| 15 · Commercial/legal review | Confirm commercial rights for every orbital-data source; validate terms, privacy, retention/deletion, business identity and applicable invoicing/tax requirements | Documented review and operating procedures before charging |
| 16 · Operations | Always-on hosting, monitoring/status page, TLE incident alerts, database backups and tested restore, documented API version/deprecation policy | Restore exercise, outage alerts and measured service behavior |
| 17 · Customers | Developer onboarding docs with working examples and actual offer; outreach and feedback from first users | First integrations and paying customers |

The roadmap budgets approximately **12 engineering hours for milestones 15–16**
(6 + 6), before deployment activation, provider setup, external review and fixes.
At four hours/week that is a three-week minimum on paper, not a launch commitment.
Customer acquisition is ongoing. Validate the data-rights question early, before
investing in a public pricing page.

## Product completeness versus optional expansion

Still missing from the original showcase: its demo GIF. Additional Doppler output,
batch predictions, notifications and natural-language queries (18–19) are optional
and should follow customer demand; they are not prerequisites for a first paid API.
The roadmap's commercial success criterion remains explicit: reconsider paid operation
if billing has been live three months with fewer than five paying customers.

## Known boundaries

- Local tests do not prove live GitHub login or production cookie/proxy behavior.
- Accounts and persistent quota accounting must be enabled explicitly.
- Sessions are local to one backend process; restarts sign users out.
- Free preview remains 100/day; paid plans use UTC calendar-month quotas,
  independently of the Stripe billing anniversary. Both paid plans have one key.
- Billing stays disabled by default. Production provider configuration and real
  money flows remain release gates.
- No live payment flow, commercial data-rights clearance, legal review, production
  restore drill or service-level commitment is established by the code tests.

## Local verification for this change

161 backend tests passed with real local PostgreSQL (none skipped), 71 Angular
tests and 6 dashboard DOM tests passed. Maven packaging and the Angular production
build succeeded. A packaged backend started with accounts/billing enabled and fake
provider credentials: the account page rendered in the browser; anonymous account
data returned 401, unprotected Checkout POST 403 and an unsigned webhook 400.
Tests use mocked/loopback Stripe responses and simulated OAuth identities.
They do not certify live GitHub login, Stripe payment, Portal/Tax configuration,
commercial data rights or production operation.
