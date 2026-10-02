# Project context for Claude

See [README.md](README.md) for what this project is and [ROADMAP.md](ROADMAP.md) for
its milestones, decisions and current status.

## Current state — 2 October 2026

Milestones 0–12 are implemented (the showcase demo GIF is still missing).
Milestone 13 adds GitHub accounts, but real production OAuth remains unverified.
Milestone 14 now adds opt-in Stripe Checkout/Portal, signed and idempotent webhooks,
and monthly Hobby/Pro quotas. Billing is disabled by default; no live payment or
provider resources were created. See [billing](docs/billing.md) for activation,
replay and the exact quota contract, and [commercial readiness](docs/commercial-readiness.md)
for release gates. Next work is milestone 15 plus real OAuth/Stripe sandbox checks;
do not confuse local test success with commercial launch readiness.

**Since 2 October 2026 the active work is phase 3 of the [roadmap](ROADMAP.md): on-orbit
separations, built on McDowell's GCAT (CC-BY), one feature at a time.** The nightly
import is `.github/workflows/daily-import.yml` calling `POST /internal/import`
(`space.nextpass.ingest`), because Render's free plan sleeps and `@Scheduled` would not
fire. The import is live in production since 2 October 2026. The separation pages
(`/separations`, `/separations/:id`, `GET /api/separations`) are phase 3.1; their copy
never names the data source, which is credited once in the page footer (CC BY 4.0).
Every backend class must live under `space.nextpass`: the component scan sees
nothing else. Tests need Orekit data; in a worktree, point `OREKIT_DATA_PATH` at the main
checkout's `orekit-data`.

NORAD numbers run to 339999 (`TleSnapshot.MAX_NORAD_ID`, Alpha-5's `Z9999`): objects
catalogued since July 2026 have six digits, and CelesTrak serves those only as OMM, never
as `FORMAT=TLE` — see "Six-digit catalogue numbers" in the README.

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

## Linear: keep the issues in step with the work, always

The backlog lives in Linear: workspace "Abdallah Abdallaoui-maane", team key `ABD`,
project **nextpass.space**. The owner wants every action reflected there automatically,
without being asked. When the Linear tools are available:

- **Before starting**, find the issue the task belongs to (`ABD-…`). None fits? Create one
  in the project (French, user story with acceptance criteria, label Feature / Bug /
  Improvement / Research / Chore) before writing code. Set it **In Progress**.
- **Branch** from `dev` with the issue's `gitBranchName`, or put the identifier in the
  branch name; the pull request targets `dev`, never `main` (see "Branches" in the README);
  write `ABD-12` in commit messages and **`Fixes ABD-12`** in the PR description, so the
  GitHub integration can move the issue on its own once it is connected.
- **The columns follow the branches.** PR opened → **In Review**, PR link attached.
  Merged into `dev` and checked there (CI green, behaviour verified) → **Validated in
  DEV**. The `dev` → `main` pull request that carries it opened → **In Review (dev ->
  main)**. That pull request merged and production checked → **Done (merged
  production)**, with a one-line comment saying what shipped and how it was checked.
  Abandoned → **Canceled** with the reason.
- **Tick the acceptance criteria** in the issue as they are met; comment anything learned
  that changes the plan (a bug found, a number measured, a decision taken).
- **New work discovered on the way** becomes its own issue, linked to the current one
  (`relatedTo` / `blockedBy`), not a silent addition to the current change.
- **"What should I do next?"** — whenever the owner asks what to work on (« quoi faire »,
  « on fait quoi », « la suite »), the answer starts from Linear, not from memory or the
  roadmap alone: list the project's open issues, skip the blocked ones, and propose the
  next by status (In Progress / In Review first), then priority, then dependencies. Say
  which issue, why it is next, and what finishing it takes.
- **ABD-20** is the reminder: when every other issue of the batch is done or canceled,
  tell the owner it is time to ask for the next batch.

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
