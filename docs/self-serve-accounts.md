# Self-serve API accounts

Milestone 13 adds GitHub login and a small dashboard at the **backend** origin's
`/account/`: create a key, see UTC daily usage, regenerate, revoke and sign out.
The demo links there directly. Sessions never cross the Vercel/Render boundary;
no credentialed CORS or frontend proxy is needed. `/api/passes` and `/v1/passes`
continue using the milestone 11 admission rules.

## Enable

1. Enable PostgreSQL following [API access](api-access.md). Flyway V3 adds
   `customer_accounts`; existing operator keys and their usage stay intact.
2. Create a GitHub **OAuth App** for this environment. Set the authorization
   callback URL to `https://YOUR_BACKEND/login/oauth2/code/github`.
3. Configure on the backend:

   ```text
   ACCOUNT_ENABLED=true
   ACCOUNT_BASE_URL=https://YOUR_BACKEND
   GITHUB_CLIENT_ID=<OAuth App client ID>
   GITHUB_CLIENT_SECRET=<OAuth App client secret>
   ACCOUNT_COOKIE_SECURE=true
   ```

   `ACCOUNT_BASE_URL` must be an exact HTTPS origin, with no trailing slash or
   path. The callback and post-login destination use this configured origin,
   never a user-supplied Host/forwarding header. Store the secret in the host's
   secret settings, never in Git or frontend configuration.
4. Deploy and visit `https://YOUR_BACKEND/account/`. Complete GitHub login, create
   a key and use the example shown in the dashboard. Regenerate it and verify
   the previous key returns 401. Revoke it and verify 401 again. Sign out and
   check that `/account/api/me` returns 401.
5. If the backend hostname changes, update the demo's account link in
   `frontend/src/app/app.html` as well as the OAuth App callback.

Without `ACCOUNT_ENABLED`, the account namespace returns 503. Enabling accounts
without OAuth credentials or the database fails startup. Disabling accounts
later does not revoke existing API keys.

For local development, use a separate OAuth App with callback
`http://localhost:8080/login/oauth2/code/github`, set
`ACCOUNT_BASE_URL=http://localhost:8080` and `ACCOUNT_COOKIE_SECURE=false`.
Leave secure cookies enabled for every HTTPS deployment.

## Contract and security

- GitHub's immutable numeric ID identifies the owner. Renaming the GitHub login
  does not create a new owner. Neither email nor a password is stored.
- The account is registered after successful OAuth state verification, before
  redirecting to the dashboard. The GitHub access token is removed from the
  authorized-client session store once identity is established.
- `GET /account/api/me` returns key ID, plan, active state, daily usage and
  limits. It never returns a hash or raw key.
- `POST /account/api/key` creates or regenerates the caller's key and returns
  its raw secret **once**. `DELETE /account/api/key` revokes it. Neither accepts
  an owner or key ID from the client.
- Rotation updates the credential on the same key row, preserving the plan,
  daily/minute limits and all counters. Account and key row locks serialize
  concurrent creation/rotation and admission. Revocation blocks subsequent
  admission; a prediction already admitted may finish.
- Self-serve keys start with **100 calls/day, 10/minute**, labelled “Free preview”.
  The existing database plan is `standard`. These are preview limits, **not**
  the monthly billing tiers hypothesized for milestone 14.
- State-changing requests and logout require a session-bound CSRF token from
  `GET /account/api/csrf`. Session cookies are HttpOnly, SameSite=Lax, Secure by
  default, and expire after 30 minutes of inactivity. Spring Security rotates
  the session ID at login. Account responses are not shared-cacheable.
- Keys live only in the current page's memory/DOM: no localStorage, sessionStorage,
  URL or example-snippet substitution. The page clears the key on sign-out,
  revocation, session expiry or navigation. Failed regeneration can leave the
  caller uncertain whether rotation committed; a fresh regeneration is the recovery.
- Session state is in memory. A restart requires login again; multiple backend
  replicas require sticky sessions or a shared session store before rollout.
- The account page deliberately loads no external scripts or analytics.

## Verification and release gate

Run the normal Maven `verify` with `TEST_DATABASE_URL`,
`TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD` pointing at a **test**
PostgreSQL database. Tests create isolated schemas. This checks migrations,
owner isolation, concurrent first creation, rotation, quota retention and UTC
reset. `AccountWebTest` checks session/CSRF enforcement, OAuth initiation and
rejection of an unsolicited callback, public assets and sanitized failures.

After `npm ci` in `frontend`, run:

```sh
node --test scripts/account-dashboard.test.mjs
```

These DOM interaction tests check request/CSRF headers, the one-time secret,
revocation, expired sessions and a failure to refresh after successful issuance.
The command also runs in CI.

**Not yet verified against live GitHub:** local security tests use a simulated
OAuth principal and test credentials. They do not establish a successful real
GitHub authorization-code exchange or production cookie behavior. Complete the
manual login-to-revocation flow above with the deployment's real OAuth App
before marking milestone 13 live. No production credentials, account, payment
or deployment were created by this implementation.
