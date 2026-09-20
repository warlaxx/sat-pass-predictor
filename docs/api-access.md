# API keys and persistent usage

The default deployment needs no database: `/api/passes` remains available without
accounting and `/v1/passes` returns 503. Enabling accounting applies limits to both
routes. Never advertise customer access before enabling it.

## Activate PostgreSQL

Provision PostgreSQL 16 or later and a dedicated database/user with migration rights.
Set these backend environment variables, then restart:

```text
API_ACCESS_ENABLED=true
API_DATABASE_URL=jdbc:postgresql://HOST:5432/DATABASE?sslmode=require
API_DATABASE_USERNAME=satpass
API_DATABASE_PASSWORD=<database password>
API_ALLOWED_ORIGINS=https://your-client.example
```

The URL must be a JDBC URL, not a `postgres://` connection string. Flyway applies
versioned migrations at startup; startup fails if enabled accounting cannot connect
or migrate. Runtime database failures return 503 rather than bypassing limits.
Leave SSL parameters appropriate to your database provider; local tests need no TLS.
CORS origins are comma-separated exact origins without trailing slashes. Empty denies
cross-origin browser calls; server clients do not need CORS. Only `/v1/**` allows
configured origins. CORS is not authentication. Keep private keys on client servers.

Enabling the database also turns on the persistent TLE store (`tle_snapshots`, added by
migration V2): one row per satellite, written on every successful fetch, read when a key
is not in memory. It follows `API_ACCESS_ENABLED` and has no switch of its own — a state
where keys are stored but elements are not would be one more thing to reason about during
an incident, for no benefit. Its effect depends on the row's age, because a stored
snapshot is treated exactly like one held in memory. Within `tle.refresh-after` (2 h) it
is served as it is, and the first call after a restart does not wait on CelesTrak. Older
than that, a refresh is attempted immediately, and the row's value is then to survive
that refresh failing: its own elements are served with their real age, until
`tle.max-age` (7 d), past which the request fails as it always has. A row is never a way
to serve elements the age rules would otherwise refuse. A database that is down costs
nothing here: reads and writes are swallowed and logged, and the store falls back to the
in-memory behaviour of the default deployment.

Customers use the backend HTTPS hostname directly, currently
`https://sat-pass-predictor-api.onrender.com/v1/passes`. A future `api.<domain>` should
point to the same backend; no frontend rewrite is required. The demo keeps `/api/passes`.
Do not cache these responses at a proxy: the backend sends `Cache-Control: no-store`.
That is about *shared* caches, and it is unrelated to the backend reusing its own
computation (see the README). A cached answer still passes through admission: quota
accounting is a `preHandle` interceptor, so it counts against the key's daily and minute
limits exactly like a computed one. A customer pays for the call, not for the CPU.

## Operator commands

Build with `cd backend && ./mvnw package`. From `backend/`, with the environment above
and the usual Orekit data directory available, run:

```sh
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none \
  --api-access.command=issue --owner=customer-reference --daily-limit=10000 --minute-limit=60
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none \
  --api-access.command=usage --key-id=<UUID>
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none \
  --api-access.command=revoke --key-id=<UUID>
```

Issuance prints the random 256-bit secret once. Store it securely; the database holds
only its SHA-256 hash, owner, plan (`standard`), active state and limits. No HTTP admin
endpoint exists. Rotation means issuing a new key then revoking the old one. Revocation
prevents subsequent admissions; requests already admitted can complete.

```sh
curl -H "X-API-Key: $SATPASS_API_KEY" \
  'https://sat-pass-predictor-api.onrender.com/v1/passes?noradId=25544&lat=45.75&lon=4.85'
```

## Limits and accounting

- Daily quota resets at midnight UTC; rate limiting uses fixed UTC minute windows.
  A boundary can admit two minutes' allowances in a short interval; this is not a
  sliding window or a concurrency cap.
- Every admitted request counts, including later validation or upstream failures.
  Invalid/revoked keys, quota rejections and CORS preflights do not count.
- Both routes use the same `passes` usage bucket when supplied the same key.
  A supplied invalid key never falls back to anonymous access.
- The anonymous demo shares one internal identity across users and replicas:
  **200 requests/day and 20/minute**. No public secret is shipped to the frontend.
  Its UUID is `00000000-0000-0000-0000-000000000001`; use the usage command to inspect it.
- Counters persist by key, UTC day and endpoint. Transactions lock the key row before
  checking and incrementing, including across backend processes and restarts.
- 401 means invalid/missing/revoked credentials. 429 Problem Details includes
  `limit` (`daily` or `minute`), `resetsAt` and `Retry-After` in seconds.
  503 means accounting disabled/unavailable or an upstream prediction failure;
  distinguish them using the Problem Details `type`.

Limits are stored per key. An operator can update `daily_limit` and `minute_limit`
with SQL (positive integers); the next admission observes the change. No billing
plans, signup or monthly quotas are implemented in this milestone.

## Verification

Normal `./mvnw verify` verifies the database-free deployment. Set `TEST_DATABASE_URL`
(and optionally `TEST_DATABASE_USERNAME`, default `satpass`, and
`TEST_DATABASE_PASSWORD`, default empty) to run PostgreSQL integration tests too.
Use a test database where the user can create schemas. Tests create a random schema
and drop only that schema afterwards. CI provides PostgreSQL 16 and runs these tests
on every pull request. They cover hashing, revocation, shared quotas, UTC resets,
persistence across service instances and concurrent admission, plus the TLE store's
round trip, its upsert guard against an older fetch overwriting a newer one, deletion,
and a corrupt row being ignored rather than propagated.

Cost per call is measured, not assumed: `scripts/measure-passes.sh` drives a running
instance and reports p50/p95 alongside the server's own propagation timer. Run it once
with `PREDICTION_CACHE_ENABLED=false` for the baseline. It targets `/api/passes`, the
anonymous demo identity, so a measurement run does not consume a customer's quota.
