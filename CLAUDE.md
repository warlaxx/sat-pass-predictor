# Project context for Claude

See [README.md](README.md) for what this project is and [ROADMAP.md](ROADMAP.md) for
its milestones, decisions and current status.

## Current state — 21 September 2026

Milestones 0–12 are implemented (the showcase demo GIF is still missing).
Milestone 13 adds GitHub accounts, but real production OAuth remains unverified.
Milestone 14 now adds opt-in Stripe Checkout/Portal, signed and idempotent webhooks,
and monthly Hobby/Pro quotas. Billing is disabled by default; no live payment or
provider resources were created. See [billing](docs/billing.md) for activation,
replay and the exact quota contract, and [commercial readiness](docs/commercial-readiness.md)
for release gates. Next work is milestone 15 plus real OAuth/Stripe sandbox checks;
do not confuse local test success with commercial launch readiness.

The backend is Java 25/Spring Boot, the frontend Angular. Run `backend/mvnw -f
backend/pom.xml verify` with JDK 25 and a dedicated PostgreSQL `TEST_DATABASE_URL`,
`TEST_DATABASE_USERNAME`, `TEST_DATABASE_PASSWORD`; database tests otherwise skip.
Run `npm run build` and `npm test -- --watch=false` in `frontend`, then
`node --test scripts/account-dashboard.test.mjs` at the repository root.
Do not put `.env` secrets in documentation or commits.

## Tooling

The TypeSafe AI plugin (`typesafe-ai`, System One models including **Jev**) is
installed and enabled in this environment. It gives typed judgments and
probabilities as a programming primitive — routing, ranking, extraction,
verification — usable wherever a feature would otherwise need an ad-hoc LLM
prompt-and-parse step. It was installed to help Claude move faster on this
project, not (yet) as a dependency of the satellite-pass-predictor app itself.

Invoke the `typesafe:typesafe-ai` skill when a task's shape fits — it reads
TypeSafe's live docs for current API/SDK details rather than relying on
memorized specifics.
