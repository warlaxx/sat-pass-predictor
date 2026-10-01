# Project context for Claude

See [README.md](README.md) for what this project is and [ROADMAP.md](ROADMAP.md) for
its milestones, decisions and current status.

## Current state — 1 October 2026

Milestones 0–12 are implemented (the showcase demo GIF is still missing).
Milestone 13 adds GitHub accounts, but real production OAuth remains unverified.
Milestone 14 now adds opt-in Stripe Checkout/Portal, signed and idempotent webhooks,
and monthly Hobby/Pro quotas. Billing is disabled by default; no live payment or
provider resources were created. See [billing](docs/billing.md) for activation,
replay and the exact quota contract, and [commercial readiness](docs/commercial-readiness.md)
for release gates. Next work is milestone 15 plus real OAuth/Stripe sandbox checks;
do not confuse local test success with commercial launch readiness.

The frontend is now prerendered at build time and bilingual: English at the root, French
under `/fr/` (`@angular/localize`), with canonical, hreflang, sitemap and robots.txt. See
[search engines and languages](docs/seo.md). Every new text needs `i18n` or `$localize`
and a French translation (`npm run i18n` in `frontend`); the build refuses a missing one.
The product is **NextPass**, at `https://www.nextpass.space` (Vercel; the apex
`nextpass.space` redirects to `www`, and canonical links and the sitemap name `www`); the
API stays on `https://sat-pass-predictor-api.onrender.com` until `api.nextpass.space` is
configured on Render. The prerendered pages were checked on the production deployment on
1 October 2026. `/starlink` lists the newest Starlink launches (`GET
/api/satellites/launches`) and their train's passes; every page carries a 1200×630
preview image (`frontend/public/og/`) and a single `<h1>`, its own title.

The backend is Java 25/Spring Boot, the frontend Angular. Run `backend/mvnw -f
backend/pom.xml verify` with JDK 25 and a dedicated PostgreSQL `TEST_DATABASE_URL`,
`TEST_DATABASE_USERNAME`, `TEST_DATABASE_PASSWORD`; database tests otherwise skip.
Run `npm run build` and `npm test -- --watch=false` in `frontend` (Node ≥ 22.22.3 or 24),
then `node --test scripts/seo-files.test.mjs` there and
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
