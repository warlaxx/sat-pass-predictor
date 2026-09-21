# Commercial readiness — 20 September 2026

Baseline reviewed: `main` / `origin/main` at `d29ee9f`. The public prediction product,
scientific regression suite, API-key quota infrastructure and cache are implemented.
This change implements milestone 13 self-serve account management. It does not deploy
it or make the API a paid service.

## Remaining work, in order

| Gate | Work | Evidence required |
| --- | --- | --- |
| Activate accounts | Provision/confirm PostgreSQL, GitHub OAuth App, exact backend origin and secure cookies; deploy | Real login → key → API call → rotation → rejection of old key → revocation → logout |
| 14 · Billing | Choose actual plans and quota periods; Stripe Checkout/Portal; verified, idempotent webhooks and replay; reconcile subscription state | Test subscription lifecycle, duplicate/out-of-order events, failed payment, cancellation and quota changes |
| 15 · Commercial/legal review | Confirm commercial rights for every orbital-data source; validate terms, privacy, retention/deletion, business identity and applicable invoicing/tax requirements | Documented review and operating procedures before charging |
| 16 · Operations | Always-on hosting, monitoring/status page, TLE incident alerts, database backups and tested restore, documented API version/deprecation policy | Restore exercise, outage alerts and measured service behavior |
| 17 · Customers | Developer onboarding docs with working examples and actual offer; outreach and feedback from first users | First integrations and paying customers |

The roadmap budgets approximately **20 engineering hours for milestones 14–16**
(8 + 6 + 6), before deployment activation, provider setup, external review and fixes.
At four hours/week that is a five-week minimum on paper, not a launch commitment.
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
- Current free preview uses daily quotas, while the proposed prices use monthly
  quotas. Billing must resolve that mismatch explicitly.
- No live payment flow, commercial data-rights clearance, legal review, production
  restore drill or service-level commitment is established by the code tests.

## Local verification for this change

149 backend tests passed with a real local PostgreSQL database (none skipped),
71 Angular tests passed, and 4 dashboard interaction tests passed. Maven packaging
and the Angular production build succeeded. On an enabled local backend, Chrome
rendered the sign-in page; HTTP checks returned 200 for the landing page/script,
401 for anonymous account data and missing API keys, and 400 for invalid demo
coordinates. Live GitHub login remains the release gate described above.
