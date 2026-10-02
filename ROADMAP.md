# Roadmap

Real budget: **~4 h/week**. Every milestone is designed to fit in 1 to 2 weeks, to be
pushed on its own and to be demonstrable on its own. No milestone depends on a future one
to make sense: if the project stops at milestone 4, what is online stays coherent.

Target: a presentable version by **end of November 2026**, mid-December with slack.

Milestones 0 to 10 build a portfolio project. **Phase 2** (milestones 11 and beyond) is a
different exercise: turning it into something that charges. The two are not the same
project and the roadmap stops pretending they are — a portfolio piece is judged on what it
demonstrates, a product on whether anyone pays. Phase 2 starts below milestone 10.

> Milestones 0 to 10 are done, except the demo GIF of milestone 9, which needs a screen
> recording rather than code. Phase 2 has started: milestone 11 (keys, quotas, persistent
> counters) and milestone 12 (answer cache, persistent TLEs, measured cost) are
> implemented, both behind the same opt-in database. Milestone 13 now implements
> GitHub self-serve accounts and key management; production OAuth activation and a real
> login smoke test remain. Milestone 14 now implements opt-in Stripe billing and
> calendar-month paid quotas; a real Stripe sandbox lifecycle and commercial launch
> gates remain. Milestone 15, commercial/legal readiness, is still open.
>
> **Active work since 2 October 2026: [phase 3](#phase-3--show-what-is-new-in-orbit),
> on-orbit separations.** Phase 3.0 (the GCAT import) runs in production; phase 3.1
> (the separation pages) is implemented, its deployment pending.

The interface has a **validated mockup** (16/09/2026) that serves as the reference for
milestones 6 to 8: `docs/interface-mockup.html`, which opens directly in a browser.

---

## Milestone 0 — Foundations (done)

Monorepo, Spring Boot 4.1.1 / Java 25, Orekit 13.1.8, Angular 22, Orekit data loading
tested, GitHub Actions CI.

---

## Milestone 1 — Computing a pass (done)

The heart of the project. Everything else is plumbing around it.

- `TLEPropagator.selectExtrapolator(tle)` from a **hard-coded** ISS TLE (no network call
  in the tests).
- Observation site: `OneAxisEllipsoid` (WGS84, ITRF frame) + `GeodeticPoint` (Lyon)
  → `TopocentricFrame`.
- `ElevationDetector` (10° threshold) + `EventsLogger` over a 24 h propagation.
- Output: a list of `SatellitePass` (AOS, LOS, duration, maximum elevation, azimuths).

**Known trap**: a TLE is expressed in the **TEME** frame, not in GCRF nor ITRF. Orekit
handles the conversion, but you have to be able to explain it.

---

## Milestone 2 — Cross-validation (done)

Comparison with Skyfield, an independent Python implementation of SGP4. Tolerances chosen,
justified, and the residual discrepancy explained in the "Validation" section of the
README. Reproducible script in `scripts/`.

This is the milestone that separates this repository from the hundreds of clones — and it
comes before the rest, not at the end.

**Still open**: Skyfield and Orekit implement *the same model*. Their agreement proves
that the implementation and the chain of frames are correct; it says nothing about the gap
to the real sky, which is dominated by the age of the TLE. A comparison against
Heavens-Above would answer the other question. Reference passes for Lyon (16–25 September
2026) are already recorded in `docs/interface-mockup.html` if you want to run it.

---

## Milestone 3 — Track sampling (done)

`SatellitePass` exposed three instants (AOS, culmination, LOS). Both views of the
interface need a **polyline**, not three points. This is done before the API is published,
so as not to have to revisit the service, the DTO, the tests and a contract already
online.

- `TrackPoint(instant, azimuthDeg, elevationDeg, rangeKm, subPoint, illuminated)` and
  `List<TrackPoint> track` in `SatellitePass`.
- `SubSatellitePoint(latitudeDeg, longitudeDeg, altitudeKm)` — **a domain type, not
  Orekit's `GeodeticPoint`**. An accepted departure from the initial plan: the domain does
  not import Orekit, and this point travels as-is into the milestone 5 JSON and then into
  the milestone 8 globe. Exposing Orekit's type would have turned its serialisation —
  angles in radians, derived fields — into an unintended public contract.
- Computation in **two passes**: one propagation over the whole window for the boundaries
  (event detectors, root finding, millisecond accuracy), then one propagation per pass
  over [AOS, LOS] with an `OrekitStepHandler` taking samples *inside* the integration
  steps. Never a `propagate()` per point.
- `subPoint` from an ITRF projection on the Orekit side. **Never recomputed in the
  browser.**
- `illuminated` pinned to `false` until milestone 10.

**Decision: a fixed 10 s step**, not a fixed number of points per pass. The instants fall
on round multiples from AOS, so they read directly as time labels on the sky chart; the
density of points says something true (a long pass has more points because it lasts
longer); and the rule fits in one sentence, which "60 points" does not — you would have to
explain why 60. Accepted trade-off: a grazing 50 s pass only gets 4 interior points and
its curve is visibly angular.

**AOS, culmination and LOS are not interpolated**: they are built from the
`SpacecraftState` objects the root search already produced, then inserted into the
polyline. Visible consequence: the culmination marker falls *on* the curve. Without that
it would float beside it — near the zenith the ISS gains several degrees of elevation in a
few seconds, and the culmination never falls on a multiple of 10 s.

**Amended at milestone 5.** The record carried the polyline *and* seven scalars beside it
— three instants and four angles — which stated the same thing twice. It now carries
`SatellitePass(TrackPoint aos, TrackPoint culmination, TrackPoint los, List<TrackPoint>
track)`, where the three remarkable points are the very instances the track holds. The
invariant becomes an equality of objects instead of a comparison of dates, the API gains
the range and the sub-satellite point at those three instants, and the culmination can no
longer be missing from the track — there is nothing left to look up. The JSON did not
change: `PassDto` already published those three phases.

**Exit criterion met**: `TrackSamplingTest` checks that the first and last points coincide
with AOS and LOS and that the elevation there equals the threshold to within 1e-3 degree,
that the culmination is in the track, that the points are chronological and no more than
10 s apart, and that every sub-satellite point is plausible for a low Earth orbit inclined
at 51.6°.

---

## Milestone 4 — TLE retrieval (done)

A frozen TLE was enough to validate the computation; it is not enough for an application.
CelesTrak republishes the ISS elements several times a day, and it is their age — not the
model — that dominates the gap to the real sky.

- `TleSnapshot(noradId, name, line1, line2, epoch, fetchedAt, source)` in the domain.
- `CelestrakTleClient`: `RestClient` to the GP API (`CATNR`, `FORMAT=TLE`), explicit
  timeouts, validation by Orekit **at retrieval time**.
- `TleStore`: a bounded store of the last known TLE per satellite.
- Tests with no network call at all (`MockRestServiceServer`, injected clock).

**Decision: this is not a TTL cache.** The initial brief said "Caffeine, 2 h TTL" and
required, two lines further down, that an unreachable CelesTrak must not stop the
application from working. The two do not hold together: with a 2 h expiry, the first
request arriving at 2 h 01 during an outage finds nothing. Hence the inversion — **nothing
expires**; the two hours trigger an *attempt* to refresh, and its failure leaves the
previous snapshot in place with its real age. Caffeine acts as a bounded store
(`maximumSize`), not as a cache. Accepted trade-off: this is an explicit departure from
the brief, so it has to be explainable.

**Two ages, never confused.** The age *since the epoch* is physical: SGP4's error grows
with it, on the order of a kilometre a day in low Earth orbit, and it is what the
uncertainty banner displays. The age *since retrieval* is operational: it only decides
whether to call CelesTrak back. A cache that expires after two hours believes it
guarantees an accuracy it does not control.

**Two limits to degradation.** Past `tle.max-age` (7 days of epoch), the prediction is
refused rather than displayed to the degree — that would be false precision. And a
satellite absent from the catalogue (`TleNotFoundException`) is **never** degraded: it is
forgotten by the store. An object that disappears from CelesTrak has most likely re-entered
the atmosphere, and propagating its last TLE would display the passes of a satellite that
no longer exists.

**Absent is not the same as unusable.** Only the `No GP data found` marker means "the
catalogue does not have this object". An empty body, a truncated response or an HTML page
mean "we did not get an answer", and are reported as a transient outage. The distinction
is not cosmetic: a not-found is permanent and makes the store drop the satellite, so
mislabelling one hiccup would throw away a perfectly valid cached TLE and tell the user
the satellite does not exist.

**Two traps of the GP API**, both encoded in the tests: an unknown NORAD number is
announced by **the body `No GP data found`**, which CelesTrak has served in two forms — in
**200** when this milestone was written, and in **404** as observed on 30 September 2026.
Either may come back, so both mean "absent"; but only with the marker — a 404 with an HTML
or empty body (a removed relay route, a proxy's error page) is still an outage. And under
load CelesTrak serves an HTML page, still in 200. Any body that does not look like a TLE is
therefore treated as an outage. The returned number is also checked against the requested one: without that check,
a response cached by an intermediary for another satellite would produce perfectly
plausible passes — and wrong ones.

**One network call per satellite.** Refreshing goes through `asMap().compute(...)`, which
Caffeine makes atomic per key: ten concurrent requests for the ISS produce one call, not
ten, which CelesTrak's documentation explicitly asks for. Accepted trade-off: the network
call happens while holding the key's lock — bounded by the timeouts, and only callers for
the *same* satellite wait.

**A backoff after a failure.** A failed refresh leaves the snapshot, and therefore its
fetch date, unchanged; the staleness test stays true, so without a backoff every incoming
request during an outage would call CelesTrak again — exactly what the paragraph above
sets out to avoid. Each entry therefore remembers its last *attempt*, not just its last
success, and no new attempt happens before `tle.retry-after`.

**Splitting the test contexts, done in the same breath.** The first `verify` of this
milestone failed 26 tests across five classes, none of which touches the network: all were
on a bare `@SpringBootTest`, so all started the whole application, so all fell over the
badly wired HTTP bean. A test must fail for what it tests. They now go through
`@OrekitTest`, a slice naming `OrekitConfig` and `PassPredictionService` — a single
context, cached, with no web layer.

The trade-off is real: nothing then checked that the *real* application starts, which is
exactly the defect that had just slipped through. Hence `ApplicationStartupTest`, the only
test that starts everything, which also checks that the beans carrying behaviour are
present — a context can start having silently omitted a `@Component`. One test starts
everything and fails alone; the others stay readable.

A corollary found along the way: `DataContext.getDefault()` is a JVM singleton, not a
bean. With two Spring contexts in the same JVM, `addProvider` was stacking two providers
over the same EOP files. `OrekitConfig` now calls `clearProviders()` first — registration
is idempotent.

**Exit criterion met**: `CelestrakTleClientTest` covers the nominal response,
`No GP data found` (in 200 and, since 30 September 2026, in 404 — a 404 without the
marker stays an outage), an empty body, an HTML page, a wrong NORAD number, an altered
checksum, a timeout and a 500. `TleStoreTest` covers the refresh window, the backoff after
a failure, falling back to the last known TLE, the absence of a fallback on the first
call, forgetting a satellite removed from the catalogue, the hard age limit and the
collapsing of concurrent calls.

**Amended on 2 October 2026: six-digit catalogue numbers.** Objects catalogued since July
2026 carry numbers above 99999, and `/api/passes?noradId=100685` answered 400 before
anything looked it up. Removing the bound was not enough: CelesTrak's GP API serves those
objects **only as OMM** — `FORMAT=TLE` answers `No GP data found`, in 404, for an object
`FORMAT=JSON` returns — so the client would have reported a live Starlink as re-entered,
and the name index, read from the TLE form of the `active` group, stopped at 69998 and had
no launch since July. Decisions: the bound becomes **339999**, `Z9999` in Alpha-5, the
last number a TLE line can carry, because snapshots stay TLE lines (persisted, served in
the API, re-parsed by Orekit). Up to 99999 the client keeps asking for verbatim TLE;
above, it asks for JSON and has Orekit write Alpha-5 lines, which then go through the same
validation as any source — the number written is the response's, so the
returned-number check still bites. Orekit's own OMM parser was tried and rejected: it
refuses CelesTrak's XML, and its TLE generation halves `MEAN_MOTION_DOT`, which CelesTrak
publishes as the TLE field (ṅ/2), not as CCSDS's ṅ. The index now reads the group's JSON
(≈ 7 MB a day, streamed record by record). Space-Track's 3LE uses Alpha-5 already, per its
documentation; that is covered by a test, not yet checked against the live service.

---

## Milestone 5 — REST API (done)

- `GET /api/passes?noradId=25544&lat=45.75&lon=4.85&alt=170&hours=48&minElevation=10`
- DTOs in ISO-8601 UTC `Instant`. Time zones are a display problem, not a computation one.
- The response carries `satellite`, `tle` (epoch, age, source, fetch date), `observer`,
  `minElevationDeg`, `computedAt` and the list of passes with their `track`.
- Jakarta validation + `@RestControllerAdvice` (errors as Problem Details, RFC 9457).
- `@WebMvcTest` tests, springdoc-openapi documentation, Swagger UI under `/docs`.

**Decision: the DTOs are not the domain.** A domain record changes when the physics or
the computation demands it; a DTO changes when a client demands it. Publishing the domain
would make every internal refactoring a breaking API change, and would forbid renaming a
domain field because a browser reads it. Accepted trade-off: one mapping to write and to
keep in step, paid for by `PassControllerTest`, which pins the shape field by field.

**Decision: the age of the TLE is computed on the server, once.** The uncertainty banner
depends on it, and a client recomputing it from `epoch` and its own clock would show a
false age the moment that clock drifts. Same reasoning for `computedAt`: the clock is read
once per request and carried along, so the start of the window and the displayed age
cannot disagree.

**Decision: the three phases are read off the track, not recomputed.** `aos`,
`culmination` and `los` are the very `TrackPoint` instances the polyline holds — the
domain guarantees the identity — so the labelled instants carry exactly the azimuth,
elevation and range of the curve drawn beside them. Nothing is searched for and nothing
can disagree.

**Decision: the bounds live in the service, and are repeated in the annotations.** The
`@Max` on the window turns an absurd request into a 400 before any propagation starts;
that is a convenience. The rule itself belongs to `PassPredictionService`, which refuses a
window beyond ten days whoever calls it — a scheduled job, a test, tomorrow's second entry
point.

**One error format, not two.** `spring.mvc.problemdetails.enabled` is off by default in
Spring Boot. Without it, a parameter the framework rejects comes back in a format
different from the one `ApiExceptionHandler` produces, with no `type` to branch on — and
a suite that only asserts status codes never notices. The property is set, and the test
asserts the media type.

**The TLE failures go through a single exhaustive `switch`** over the sealed
`TleException`. Three separate `@ExceptionHandler` methods worked; they would also have
turned a fourth subtype into a silent 500. Now the compiler refuses the fourth subtype
until the switch names it.

**Exit criterion met**: the JSON documented in `docs/interface-mockup.html` (section "What
the API must return") is served as-is, and `PassControllerTest` covers the documented
shape, the server-side age, the three phases, the defaults, the four error cases and the
parameter bounds.

---

## Milestone 6 — Frontend: shell and list (done)

Visual reference: `docs/interface-mockup.html`. **The mockup is the visual and
behavioural target, not a template to copy**: its DOM is built in imperative JavaScript,
which has no place in an Angular component.

- Design tokens in `styles.scss` (CSS variables): a single dark palette, a colour scale by
  elevation, three IBM Plex typographic roles. Every numeric value in `IBM Plex Mono` with
  `font-variant-numeric: tabular-nums`.
- `standalone` components, `OnPush`, state through **signals**, **zoneless** application.
- `httpResource` for the API call, with its three states actually drawn (loading / error /
  empty) — no infinite spinner.
- Form: satellite, position, window, minimum elevation. Browser geolocation optional,
  manual entry always possible.
- `TleBannerComponent`: epoch, age, expected drift, uncertainty on AOS.
- `PassTableComponent`: local time **and** UTC, duration, maximum elevation, azimuths as
  compass points. Rows focusable and activatable from the keyboard.
- `PassRibbonComponent`: one bar per pass, height = maximum elevation.

Design tokens, the API contract types and their fixture test, the `httpResource` service,
the shell with its query form and browser geolocation, `TleBannerComponent`,
`PassTableComponent`, `PassRibbonComponent`, and the responsive pass at 880 px and 640 px.
36 frontend tests, `ng build` and `ng test` green.

**Decision: a night is named after its evening.** The ribbon groups by observing night,
not by calendar date: a pass at 02:00 on the 23rd belongs to the night of the 22nd, not to
a column of its own wedged between two that belong to the same night out. Shifting the
local time back twelve hours before taking the date does exactly that, with no special
case at a month or year boundary. Empty nights are kept — a night with nothing is a
result, not a gap to close up, and dropping it would make two nights a week apart look
adjacent.

**Decision: the bars are scaled against 90 degrees, not against the best pass.** A
relative scale would make a mediocre evening look excellent whenever the window holds
nothing better, which is the opposite of what the ribbon is for. Accepted trade-off: a
window of nothing but grazing passes is a row of stubs — which is the honest picture.

**Decision: geolocation never becomes the only way to give a position.** It is a
permission the user can refuse, a sensor that can fail and a call that can hang; each has
its own message, and the fields stay editable throughout. `enableHighAccuracy` is off:
metres are pointless four hundred kilometres below the satellite, and a GPS fix would cost
battery and seconds for nothing. The altitude is taken as the API returns it — the
Geolocation spec measures it above the WGS84 ellipsoid, which is exactly the datum
`ObserverLocation` expects.

**Decision: narrow screens scroll the table rather than lose columns.** It is the
accessible equivalent of two drawings; dropping the azimuths to make it fit would take the
information away from precisely the readers who have nothing else.

**Decision: the API types are written by hand, and pinned by a fixture.** Generating them
from `/v3/api-docs` would remove the risk of drift and add a generator, a build step and a
pile of generated code to review. Six records do not justify that — provided the drift is
caught rather than hoped away. `passes.contract.spec.ts` holds the exact response
`PassControllerTest` makes the backend produce, and compares key sets rather than reading
fields, so a renamed field fails a test instead of going unread. The two suites pin the two
ends of one contract.

**Decision: the fonts are bundled, not fetched from Google.** The Angular build inlines a
Google Fonts stylesheet at build time, so the build fails when that host is unreachable —
which is how the decision came to be made rather than assumed. Fetching them at runtime
hands every visitor's IP to a third party, and typography validated in IBM Plex that falls
back to Helvetica because a CDN hiccuped is not the same page. `@fontsource`, latin subset,
seven faces: 66 kB of bundle and 940 bytes of critical CSS. Same reasoning as the sky chart
having no external dependency, applied to the type.

**Decision: four states, four answers.** Idle, loading, error and empty are drawn as four
different things. A page that answers "still loading", "the server said no" and "no pass in
this window" with the same spinner answers the wrong question twice out of three. The error
state reads the `type` of the Problem Details body, which is what tells an unknown
satellite from a CelesTrak outage — two answers that share status 503.

---

## Milestone 7 — Sky chart (done)

The view that answers "where do I look up from Lyon". **No external dependency**: SVG
rendered by the Angular template from `computed()`.

- Polar disc: edge = horizon, centre = zenith, north at the top. Circles at 30° and 60°, a
  dashed circle at the requested threshold, compass points outside.
- Track drawn from `track` in a neutral colour until illumination is computed in milestone
  10. Minute dots, with alternate minute labels to reduce collisions.
- `PassViewer`: a **single clock** for the whole page, actual time carried by
  a signal, play / pause / scrubber, and a continuous readout of time, azimuth, elevation
  and range. Implemented by `PassClock` with actual UTC milliseconds, not a phase index.
- `requestAnimationFrame`, never `setInterval`. Honour `prefers-reduced-motion`.
- Accessible text equivalent: the pass table, with a `<caption>` that says so.

Validated with the real local API in the browser at desktop and mobile widths. Selection,
play/pause and phase readouts were exercised; 43 frontend tests and the production build
pass. The neutral illumination treatment is intentional until milestone 10.

This is the image that serves as the demo GIF in the README. It is worth more than three
paragraphs of description.

---

## Milestone 8 — 3D globe (done)

The view that answers "where is the ISS, and who else can see it". Complementary to the
sky chart: one is in a topocentric frame, the other in a terrestrial frame.

**Non-negotiable condition, met: the globe computes nothing.** `GlobeComponent` reads
`track`, `subPoint`, `observer` and `minElevationDeg` from the same `PassDto` the sky chart
already has, and shares its `PassClock` — one instant drives both views. three.js (r128,
UMD), a sphere, Natural Earth 110 m coastline, a graticule and a translucent halo; a
`DirectionalLight` positioned at the subsolar point makes the day/night terminator fall out
of ordinary Phong shading, with nothing hand-drawn for it. Observer marker, line of sight,
and pointer-driven rotation (`OrbitControls` is not in the UMD bundle, so drag handling is
about thirty lines of pointer events, same as the mockup).

**Decision: the ground track is split by the *ground's* day/night line, not the
satellite's.** The mockup's globe coloured the track by the ISS entering Earth's shadow —
exactly the fact `illuminated` will carry from milestone 10, and exactly the fact this
project refused to fake for the sky chart in milestone 7. Colouring the real globe the same
way here would have quietly reintroduced it through the back door. What *is* available now,
without propagating anything, is where the Sun already is: a low-precision subsolar-point
formula (accurate to a fraction of a degree — plenty for a lighting direction, useless as
an ephemeris) says whether a given `subPoint` sits on the day or the night side of Earth.
That is a fact about the ground, not about the satellite, and it is what the two-colour
track now shows. The legend says so explicitly, so the two "shadows" — this one and the
sky chart's future one — are never read as the same fact.

**Decision: no fabricated context track.** The mockup extends the ground track a couple of
minutes past AOS and LOS for visual continuity, by slerping the same fabricated sky
positions it uses everywhere else. There is no equivalent real data outside `[AOS, LOS]`,
and inventing a plausible-looking extension would be exactly the kind of browser-side
propagation milestone 8 exists to refuse. The real globe draws the track it was given and
stops there.

**Decision: the visibility circle uses the real altitude and the real threshold.** The
mockup hard-codes `HSAT = 420` km and a 10° threshold because it has no API to ask.
`visibilityRadiusDeg(altitudeKm, minElevationDeg)` takes both from `subPoint.altitudeKm`
and the query's `minElevationDeg` at the current instant, so a different satellite or a
different threshold draws a correctly sized circle instead of a plausible-looking one.

**Decision: the Sun's position is fixed at culmination for the whole pass.** Recomputing it
every frame would cost nothing, but a pass lasts a few minutes, over which the true subsolar
point moves a fraction of a degree — not worth the churn for a terminator that is already a
simplification. Consistent with the decision above: this view does not chase precision it
has already declined to promise.

**Decision: three.js is not bundled.** It is loaded from cdnjs at the same pinned `r128` as
the mockup, the only external runtime dependency in the frontend. Bundling it would put
roughly 600 kB used by one component into every visit, including those that never open the
globe. `@types/three`, pinned to the matching `0.128.0`, is a devDependency only — type
information at compile time, nothing in the shipped bundle — so the component stays fully
typed against a library it never imports as a value. The **mandatory fallback** this
implies is not a hypothetical: `THREE_LOADER` is an injectable indirection precisely so a
test can exercise "three.js failed to load" without depending on cdnjs being reachable in
CI, and the same code path is what a real network failure hits in production. The sky chart
stays independent of all of this, which is exactly why a reader is never left with nothing.

**Decision: the coastline is a static asset, not a bundled constant.** The mockup inlines
Natural Earth 110 m coastline as a JavaScript literal because it is a single file with
nowhere else to put it. Angular has somewhere else: `public/coastline-110m.json`, fetched
once from the app's own origin. Unlike the Google Fonts case in milestone 6, this is not a
third-party request — the file ships with the build — so there is no visitor-IP trade-off
to weigh, only a smaller `main.js` and a JSON payload the browser can cache on its own.

**Exit criterion met**: `globe-geometry.spec.ts` pins the subsolar-point formula, the
destination-point spherical trigonometry, the visibility-radius formula against the
documented ISS figure (≈ 12.5°), and the `subPoint` interpolation including across the
antimeridian. `globe.spec.ts` pins the one behaviour worth a component test — the mandatory
fallback — via the injectable loader. The WebGL rendering itself has no jsdom equivalent to
assert against; it was checked by hand against the real local API, at desktop and mobile
widths, including drag-to-rotate and play/pause staying in sync with the sky chart.

**Addition (September 2026): the imaging swath, suggested by a SatDump user.** For a
weather satellite, the question before a pass is not only where it is but what the image
will show. The globe now draws the instrument's swath as a translucent band along the
track. Three decisions keep it honest:

- **The width is a property of the instrument, not of the orbit**, so it lives in a small
  table keyed by NORAD number (`imaging-swath.ts`): MSU-MR 2,800 km for Meteor-M N2-3 and
  N2-4, AVHRR 2,900 km for Metop-B, nominal figures from WMO OSCAR. NOAA-15/18/19 (APT,
  decommissioned in 2025) and Metop-C (AHRPT switched off) are left out on purpose: a
  swath nobody can receive would only mislead. Other satellites draw no band.
- **The band covers AOS to LOS at the chosen threshold**, because that is the stretch the
  station hears. Raising the threshold to the elevation at which the user's setup actually
  decodes shortens the band accordingly; the legend says it is an estimate.
- **It is still no propagation in the browser.** `swathRows` only offsets each API
  `subPoint` perpendicular to the track heading, and subdivides each row so the mesh
  follows the sphere rather than cutting through it.

---

## Milestone 9 — Showcase pass (done, apart from the demo GIF)

- Multi-stage Dockerfiles + `docker-compose.yml` (`orekit-data` downloaded at build time,
  not at runtime).
- README: architecture diagram, CI badge. **Remaining: the demo GIF** — a screen
  recording of a pass being played on the sky chart and the globe together.
- "Physical model" section: frames (TEME / GCRF / ITRF), time scales (UTC / TAI / UT1),
  limitations of SGP4, role of the EOP.

**Decision: the API image is the one Render already builds.** `docker-compose.yml` reuses
`backend/Dockerfile` as it is, rather than a local variant. One image means the thing a
reader runs with one command is the thing that is deployed, and a Docker-only fix cannot
drift away from production.

**Decision: nginx stands in for Vercel, and does the same three things.** Static files,
`/api` forwarded server-side (so the browser sees one origin and the API still needs no
CORS), and the same long-lived caching for the fingerprinted bundle. The API address is
an environment variable substituted at container start, not a rebuild.

**Decision: the healthcheck speaks HTTP through bash, not curl.** The JRE runtime image
has neither curl nor wget. Installing one would grow the image Render ships for a purely
local convenience; bash's `/dev/tcp` can read `/actuator/health` in one line. The
frontend waits for that check, so the first page load never races the JVM's start-up.

**Decision: Vercel Web Analytics is answered with an empty 204 outside Vercel.** `main.ts`
injects its script unconditionally; under nginx the request is a guaranteed 404 and a red
line in every console. An empty response lets the script tag load nothing and sends
nothing anywhere. (The location needs `^~`: without it, the `.js` caching rule, a regex,
wins over the prefix and the 404 comes back — which is exactly what happened first.)

**Decision: the root `.dockerignore` excludes `orekit-data/`.** The build context is the
repository root (Render's constraint), and without it every build uploaded 100 MB of data
to the daemon only for the image to download the same archive itself.

**Exit criterion met**: `docker compose up --build` from a clean checkout builds both
images, the API turns healthy, and through nginx the page loads, `/api/passes` returns
real passes from a live CelesTrak TLE, an invalid query returns Problem Details, and the
globe renders — checked by hand in a browser, with no failed request left in the console.

---

## Milestone 10 — Differentiation (optional, but this is where the value is)

- [x] **Potentially naked-eye visible passes**: `illuminated` now comes from Orekit's
  ellipsoidal Earth / finite solar disc eclipse geometry. Partial eclipse counts as not
  fully illuminated. The new additive `track[].visible` flag also requires the Sun's
  geometric elevation at the observer to be at most -6° (civil twilight).
- [x] The table and selected pass identify favourable samples. Sky chart, panorama and
  globe colour satellite illumination, including passes entirely in eclipse. Sunlight
  alone is never labelled as naked-eye visibility.
- [x] Several satellites, next favourable window over 7 days: up to five unique NORAD
  IDs, independent 168 h requests, first consecutive favourable sample interval for each
  satellite, ordered by visibility onset. Partial failures stay explicit; selecting a
  result reuses its prediction in the existing views. Browser requests are cancelled on
  restart. Searched observer/threshold and element age at the opportunity remain visible.
- [x] **Calendar export**: the pass list downloads an iCalendar (`.ics`) file of the
  potentially visible passes on screen — one event per pass with a favourable sample,
  spanning its first favourable interval, rise/peak/set in the description, a reminder
  10 minutes before. Built in the browser from the response already loaded: no request,
  no quota. UIDs derive from NORAD id, AOS and observer, so re-importing the same
  prediction updates events instead of duplicating them. Times are UTC; the calendar
  application localises them.

**Discovery scope:** this is an on-demand comparison through the existing API, not a
catalogue scan or a new batch endpoint. Each request has its own computation time. The
first opportunity is ranked among successfully computed satellites only. Tests cover ID
bounds/deduplication, sample-based intervals, ordering, partial failures, empty results,
request cancellation and opening a result without another fetch.
- PostgreSQL is deferred to milestone 11, where keys and usage counters justify it.

**Sampling limit:** flags are evaluated on the existing 10 s grid and at AOS, culmination
and LOS. Eclipse/twilight transitions are not root-refined; a favourable interval shorter
than the grid spacing can be missed. The shadow-entry label is approximate. `visible`
is a geometric opportunity, not a promise: magnitude, attitude, atmosphere, weather and
local obstructions are not modelled.

**Verification:** `OpticalVisibilityTest` uses a frozen TLE over 48 h, checks eclipse
geometry against an independent spherical angular-disc calculation away from the limb,
and checks observer darkness by projecting the solar direction on the geodetic zenith.
It requires daytime sunlight, favourable samples, eclipses and within-pass transitions.
API tests pin both boolean values; frontend tests distinguish sunlit daytime passes from
favourable ones and render fully eclipsed passes as shaded. Existing pass regression and
sampling checks still apply; this does not independently validate brightness or the sky.

---

# Phase 2 — From MVP to a product that charges

## The premise, stated plainly

Three facts decide everything below, and none of them is about code.

**The consumer market is already free.** Heavens-Above, N2YO, Stellarium and a dozen phone
apps predict passes for nothing, and have for fifteen years. A stargazer will not pay
€5/month for a prettier version of what their phone already does. Building a subscription
for that audience is the default plan and it is the one that fails.

**The API is the product.** The thing that is hard to obtain is not a pass chart, it is a
*reliable, documented, supported* pass-prediction endpoint that someone else's software can
depend on. That is what `/api/passes` already is: Problem Details, an OpenAPI contract,
validated inputs, a TLE source chain that survives one source going dark. Milestones 0 to 8
did not build a website with an API behind it — they built an API with a demo in front of
it. Phase 2 accepts that inversion instead of fighting it.

**The buyers are narrow and identifiable.** Amateur-radio satellite operators, cubesat and
ground-station teams, astrophotography and drone tooling, museums and planetariums,
students on funded projects. Hundreds of them, not millions. That is a feature: hundreds of
findable people with a budget beats millions of anonymous people without one.

Two constraints bound every plan that follows.

**The arithmetic.** At €29/month, thirty-five paying customers make €1 000/month. That is
the whole target, and it is a number of *conversations*, not a number of features. Every
milestone below is graded on whether it moves that number.

**The budget is still ~4 h/week.** Milestones 11 to 16 are about 40 h: four to five months
before the first euro can legally be taken. Nothing here shortens that; the order exists so
that no hour is spent on something a later decision would throw away.

---

## Milestone 11 — Know who is calling (implemented; deployment opt-in)

Nothing can be sold that cannot be counted and cut off. This is the first milestone, before
any payment page, because every one after it depends on identity existing.

- **API keys**, hashed at rest (the database stores a hash, never the key; it is shown once
  at creation and never again). A key carries a plan, an owner and a state.
- **Quota and rate limit per key**, refused with `429` and a Problem Details body that says
  *which* limit was hit and when it resets — the same contract style as the rest of the API.
- **A usage counter per key and per day.** Without it there is no invoice, no fair-use
  argument and no idea which endpoint costs money.
- The anonymous web app keeps working, on a shared public key with a small quota. The demo
  must never require a signup to be convincing.

**This is where PostgreSQL finally arrives**, and milestone 10 was right to defer it: keys,
owners and usage counters are a real need, "caching a TLE" was not. One database, arriving
once, for a reason that can be stated in a sentence.

**Implemented:** `/v1/passes` requires `X-API-Key`; `/api/passes` keeps the anonymous
demo on a shared identity (200/day, 20/minute). PostgreSQL admission locks serialize
checks and increments across instances. Keys are issued/revoked through an operator CLI;
only SHA-256 hashes are stored. UTC daily counters and fixed minute windows persist.
Admission counts even if subsequent validation or prediction fails; rejected admission
does not count. See [the activation runbook](docs/api-access.md). Database access is
explicitly disabled by default to preserve the existing deployment.

**API hosting decision:** clients call the backend directly at `/v1/passes`, with an
exact CORS allowlist. The existing Render hostname is usable now; configuring a custom
`api.<domain>` and provisioning PostgreSQL remain deployment actions.

**Original hosting rationale:** Today the frontend reaches
`/api/*` through Vercel, server-side, which is why `render.yaml` says CORS is unnecessary
and to keep it that way. A customer calls the backend *directly*, from their server or
their browser. That comment stops being true the day the first key is issued: the API needs
its own hostname (`api.<domain>`), a CORS policy that is deliberate rather than absent, and
a versioned path (`/v1/passes`) so that the contract can move without breaking a paying
integration. Cheap now, expensive after the first customer.

---

## Milestone 12 — Serve a call without paying for it twice (done)

Margin is decided before revenue. Every `/api/passes` call today propagates an orbit over
the whole window and samples it at 10 s. That is milliseconds of CPU, but it is *linear*:
a thousand calls for the same satellite over the same city on the same day do the same work
a thousand times, on a container sized for a free plan.

- [x] **Cache the answer**, keyed on satellite, observer, window and minimum elevation,
  invalidated by the elements rather than by a clock: the entry records the two TLE lines
  it was computed from, and a republished TLE is a miss. That is the physically correct
  rule — the elements are the only input that changes on its own. A maximum age
  (`prediction-cache.max-age`, 5 min) sits on top of it, and it bounds something else
  entirely: the window starts at the instant of the request, so an entry served later
  describes a window that starts slightly in the past. The response has always carried
  `computedAt`, so the answer stays self-describing rather than becoming wrong.
- [x] **Cache the TLE itself** in PostgreSQL (`tle_snapshots`, one row per satellite).
  A stored row is treated exactly like elements held in memory, which is what decides
  what it saves: within `tle.refresh-after` it is served as it is and the first call
  after a restart owes nothing to CelesTrak; past it, a refresh is attempted at once and
  the row only protects against that refresh failing — by serving its own elements with
  their real age, up to the seven-day limit past which nothing is served. The row is
  deleted only when the catalogue says the object is gone. Follows `api-access.enabled`;
  the default demo is unchanged.
- [x] Measured before and after, with `scripts/measure-passes.sh` and the two meters it
  reads (`nextpass.predictions`, `nextpass.prediction.duration`).

**The measurement.** 200 sequential calls, ISS over Lyon, 48 h window, JDK 25 on an
Apple M-series laptop — an order of magnitude, not a datacenter benchmark:

| Workload | p50 | p95 | Propagation per 1 000 calls |
|---|---|---|---|
| Same request repeated, cache off | 112.7 ms | 121.9 ms | 112 s |
| Same request repeated, cache on | 2.4 ms | 3.3 ms | ~0 s |
| 200 distinct observers, cache on | 113.4 ms | 120.8 ms | 112 s |

The last column is elapsed time inside the propagation and **not** CPU time: a Micrometer
timer measures a duration, and nothing here samples a thread's CPU clock. On a
single-threaded run the two are close, and the distinction matters the moment the number
is used to size a container.

Two numbers matter here and the third is the honest one. A repeated call costs about
**a fiftieth** of what it cost, and 112 s of propagation per thousand calls becomes
nothing. The
third row is the workload the cache cannot help — every request genuinely different — and
it shows the cache costs nothing measurable when it never hits: 113.4 ms against 112.7 ms
is inside the noise of the first row. That is the result to keep: the free tier is now
bounded by *distinct* requests, not by requests.

**What was deliberately not done.** The observer is not rounded onto a grid. Serving one
caller a computation made for another position means either echoing coordinates they did
not send, or echoing theirs while the passes belong elsewhere — and this API publishes the
observer it computed for. A 0.01° grid is about 1.1 km, some 0.16° of elevation at
low-orbit range, larger than the refraction already declined; the workload that actually
repeats, one integration polling the same coordinates, hits with exact keys anyway.

**What this does not change.** Quota accounting is a `preHandle` interceptor, so a cached
answer still counts against the key's daily and minute limits: a customer pays for the
call, not for the CPU. `Cache-Control: no-store` still goes out, because a shared proxy
cache serving one customer's answer to another is a different thing entirely from this
process reusing its own work. And the honest framing of the whole milestone: it produces
no visible feature. It is what makes a free tier possible without the free tier being the
thing that kills the service.

---

## Milestone 13 — Self-serve signup (implemented; production activation pending)

A key that requires emailing the author is not a product, it is a favour. Until signup is
self-serve, every customer costs an hour of your 4 h/week.

- Email + magic link, or GitHub OAuth. **Not** passwords: no reset flow, no storage
  liability, no 2FA debate, for an audience that has a GitHub account anyway.
- A dashboard with exactly four things: the key, usage against quota, the plan, a button to
  regenerate. Nothing else. Every extra screen is time not spent on milestone 17.
- Keys are created and revoked by their owner, without you.

**Implemented:** GitHub OAuth through Spring Security, with a backend-hosted dashboard
at `/account/`. The immutable GitHub ID owns one key. Creation, regeneration and
revocation are self-serve; regeneration preserves usage and limits on the same key row.
The raw key is shown only once. Authenticated mutations and logout require CSRF,
account responses use `no-store`, and database failures fail closed.

**Preview offer:** 100 calls/day and 10/minute, displayed as “Free preview” (the
existing internal `standard` plan). The monthly commercial tiers below are still
hypotheses; this milestone does not promise or implement them.

**Verification:** PostgreSQL tests cover owner isolation, concurrent first issuance,
rotation/revocation and retained quotas. Web tests cover the session/CSRF boundary,
OAuth state rejection and the public landing page. DOM tests cover key display,
revocation and session expiry. Existing backend/frontend tests and builds also run.

**Remaining release gate:** configure the production PostgreSQL/OAuth App, then run
one real GitHub login → issue → API call → regenerate → revoke → logout flow.
Local OAuth tests use a simulated identity; they cannot certify a live provider exchange.
Sessions are in memory: use one instance, or configure sticky/shared sessions before
scaling. See [the activation runbook](docs/self-serve-accounts.md).

---

## Milestone 14 — Billing (implemented; Stripe sandbox activation pending)

- **Stripe Checkout** for subscription, **Stripe Customer Portal** for upgrade, downgrade
  and cancellation. Neither is a screen you build; both are a redirect. This is the single
  largest saving in phase 2 — do not build an invoice, a card form or a dunning email.
- **Webhook** → plan applied to the key. The webhook is the source of truth, not the
  redirect back from Checkout: the customer closes the tab, the payment succeeds anyway.
- **Flat tiers, not metered billing.** Metered means usage reporting, proration, surprise
  invoices and support mail. Flat means a quota and a `429`. A tier can be changed later;
  an unhappy customer holding a €400 surprise invoice cannot be un-made.
- Idempotent webhook handling and a replay path. Stripe delivers twice; it is documented,
  it will happen, and a double upgrade is noticed by nobody while a double *downgrade* is.

**Implemented on 21 September 2026:** backend-created Stripe Checkout and Customer
Portal sessions, authenticated/CSRF-protected billing buttons, signed webhooks,
transactional event deduplication and fresh subscription reconciliation. Replays and
out-of-order deliveries cannot restore an old plan. Revoked keys stay revoked.
Paid entitlements expire at the subscription period end if renewal events are lost.

**Quota decision:** retain Free preview at 100/day and 10/minute. Hobby receives
25,000 per UTC calendar month and 30/minute; Pro 250,000 per UTC calendar month and
120/minute. Both currently have one key. Usage survives every plan change and key
rotation. Paid periods in Stripe and quota calendar months are deliberately distinct.
Actual prices come from the configured Stripe prices; no public price is promised.

**Verification:** PostgreSQL, signed HTTP webhook, Stripe SDK loopback and dashboard
interaction tests cover quota boundaries, duplicates, concurrent processing, rollback,
failed payment states, cancellation, Checkout reuse and owner isolation.

**Remaining:** configure and exercise a real Stripe sandbox subscription lifecycle,
including portal invoicing/proration and Tax settings, then complete milestones
15–16 before accepting payment. No live charge, deployment or Stripe resource has
been created. See [billing setup and replay](docs/billing.md).

The original offer below remains a hypothesis, **not the implemented contract**:

A hypothesis to test, not a truth: **Free** 1 000 calls/month, shared key, no SLA ·
**Hobby €9** 25 000 calls, one key · **Pro €49** 250 000 calls, several keys, e-mail support
· **Business €199** custom volume, invoice, response-time commitment. The gap between €9 and
€49 is deliberate: the €9 tier exists so that saying yes is easy, not to make money.

---

## Milestone 15 — What makes it legal to sell (≈ 6 h · 1–2 weeks)

Unglamorous, non-optional, and cheaper before the first payment than after.

- **Terms of service and privacy policy.** Selling from France means GDPR: a legal basis, a
  retention period, a deletion path, a named data controller. *Mentions légales* are
  mandatory for a French commercial site.
- **VAT.** Stripe Tax handles EU VAT-on-digital-services and OSS reporting. Turn it on at
  the start; retrofitting VAT onto existing subscriptions is genuinely painful.
- **The status of the underlying data — check this before writing a pricing page.** The TLE
  chain is CelesTrak first, Space-Track last. Space-Track's user agreement constrains
  redistribution of its data, and "my customer receives positions derived from it" is not
  obviously outside that. CelesTrak's terms are their own question. Two outcomes are
  acceptable: confirm that derived predictions may be served commercially, or disable the
  Space-Track source for paid traffic and say so in the docs. Discovering this after
  thirty customers is the one mistake in phase 2 that cannot be fixed by writing code.
- A company form, eventually. *Micro-entreprise* is enough to invoice legally at this scale
  and takes an afternoon.

---

## Milestone 16 — Reliability a customer can hold you to (≈ 6 h · 1–2 weeks)

- **Leave the free Render plan.** It sleeps; a sleeping instance answers the first call in
  thirty seconds. A hobbyist shrugs, an integration times out and opens a support ticket.
  The first paid tier pays for the paid instance — that is the actual reason to have one.
- **Uptime monitoring and a public status page.** Being able to point at ninety days of
  green is a sales argument for an API, and the cheapest one available.
- **Alerting on the TLE chain.** Every source dark means every prediction stale. Today that
  is a log line; for a paying customer it is an incident.
- **A backup of the database and a restore that has been run at least once.** A backup never
  restored is not a backup.
- **A deprecation policy in writing**: what `/v1` guarantees and how long a version survives.
  Integrators buy predictability more than they buy features.

Only now does an SLA mean anything; before this it is a sentence, not a commitment.

---

## Milestone 17 — The first ten customers (ongoing, from milestone 14)

The milestone every engineering roadmap omits and the only one that determines whether any
of the others mattered. It is not four hours; it is a habit.

- **Publish where the buyers already are**: AMSAT and amateur-radio satellite forums,
  r/amateursatellites, cubesat and ground-station mailing lists, the Orekit and Skyfield
  communities, Show HN. The cross-validation of milestone 2 is the credential here — it is
  the part of this project that an engineer reads and trusts.
- **Write the docs as the sales page.** For a developer product, the documentation *is* the
  landing page: a working curl on the first screen, a client library snippet, a plan table.
- **SEO on the long tail**: "ISS pass prediction API", "satellite pass API", "SGP4 REST API".
  Low volume, and every visitor is someone with the problem.
- **Talk to the first ten by hand.** Ask what they were using before and what nearly stopped
  them integrating. Ten answers reorder milestone 18 better than any guess.

**Kill criterion, agreed in advance**: three months after billing is live, fewer than five
paying customers means the product hypothesis was wrong — not that the marketing needs more
effort. Keep the API free, keep it in the portfolio, stop spending the 4 h/week on billing.
Deciding this now is what stops it becoming a two-year sunk cost.

---

## Milestone 18 — What people actually pay extra for (≈ 12 h, ordered by what milestone 17 hears)

Do not build this list in advance. It is the menu, and customers choose from it.

- **Visible passes** — milestone 10's illumination, which is the one prediction the free
  tools get wrong or omit. The strongest single candidate for the paid tier.
- [x] **Doppler shift and range rate per track point** — amateur radio's actual requirement, and
  the track already carries range at 10 s. A small change, a whole audience.
  *Done:* `rangeRateKmS` on every point and phase, from Orekit's velocity rather than
  differenced ranges (exact at AOS/LOS). An optional `frequencyMhz` adds `dopplerHz`,
  first order (`(v/c)²` is ~0.1 Hz at 145 MHz). The scaling lives in the DTO, so the
  cached prediction serves every frequency. `TrackSamplingTest` checks the rate against
  the sampled ranges (trapezoid, 50 m/s bound; 10 m/s measured) and its sign at AOS/LOS.
  The viewer shows the live range rate, and the live Doppler shift when the form's
  optional downlink frequency is filled. A pass profile draws elevation and Doppler (or
  range rate) against time on the shared clock, marks the closest approach where the
  shift crosses zero, and downloads the pass's samples as CSV, built in the browser.
- **Webhooks and scheduled alerts** — "call my endpoint 20 minutes before the next pass".
  Sells on the customer's ops burden, not on orbital mechanics.
- **Batch endpoints** — many satellites or many sites in one call. Cheap to serve once
  milestone 12 exists, and the reason a business tier is worth €199.
  **Implemented on 30 September 2026, ahead of the demand this list waits for** — see
  below.
- **Longer windows and a full catalogue** beyond the ISS, with the honest SGP4 caveat.
- **Ground-station pointing exports** — Gpredict, SatNOGS and TLE-format outputs.

### Batch endpoint (implemented)

`GET /v1/passes/batch?noradId=25544,20580&site=45.76,4.83,170&site=-33.92,18.42` predicts
every satellite for every site: at most 10 satellites, 10 sites and 25 predictions.
Each entry holds either the `/v1/passes` body or the Problem Details that call would have
returned. Runbook: [batch requests](docs/api-access.md#batch-requests).

**Decision: a batch is billed per prediction, all or nothing.** A batch of twenty costs
the CPU of twenty calls, so it counts twenty against the daily, monthly *and* minute
limits. Admitting part of it would hand the caller a result they did not ask for; a
batch that does not fit is refused whole and consumes nothing. One larger than the key's
per-minute limit can never fit, so it gets a 400 `batch-exceeds-rate-limit` rather than a
429 whose `Retry-After` would be a lie. What the batch sells is one round trip and one
consistent answer, not a discount — pricing a discount is a milestone 17 question.

**Decision: `GET` with query parameters, not a JSON `POST`.** Admission is a `preHandle`
interceptor, which runs before any body is read, and it must know the price before the
controller runs. The query string is available there, and one parser (`BatchQuery`)
serves both the interceptor and the controller, so what is billed and what is computed
cannot drift apart. It is also the existing `/v1` style, and the CORS allowlist already
permits only `GET`. Spring's binding splits a single comma-separated value into a list,
which would break `site=lat,lon,alt`, hence the parser reads the raw parameters.

**Decision: one failed satellite is an entry, not a failed batch.** Nine satellites
computed and one unknown one is a result; turning it into a 404 would throw away work
already paid for. Validation of the batch itself stays a whole-request 400.

**Decision: one TLE read and one clock read per satellite.** All sites of one satellite
share the elements and `computedAt`, so they are comparable, and an unknown satellite
costs one catalogue lookup rather than one per site — the store forgets objects the
catalogue does not have, and would ask CelesTrak again for each site otherwise.

**Decision: sequential, bounded, keyed only.** Predictions run one after the other;
the 25-prediction cap, not parallelism, bounds the response time, and one caller cannot
take every core. Measured worst case (25 cache misses over 240 h, M-series laptop):
7.5 s and 13.8 MB with tracks; 8 ms and 0.5 MB from the cache with `track=false`. There
is no `/api` alias: the anonymous demo's shared budget is not to be spent in bulk.

**Verification:** `BatchQueryTest` pins parsing, deduplication, bounds and the caps;
`BatchPassControllerTest` the JSON, partial failures and the admission price;
`AccessServicePostgresTest` per-prediction counting, all-or-nothing admission and the
oversized-batch refusal on real PostgreSQL; `PassQueryServiceTest` that several sites
share one TLE read and match their single calls. Checked by hand against a running
instance with live CelesTrak elements.

---

## Search engines and a French edition (done; production check pending)

A single-page app answers a crawler with an empty `<app-root>`, which is what the frontend
did until now; the SEO line of milestone 17 could not start on top of that. Details and
the remaining work, by order of value: [search engines and languages](docs/seo.md).

**Decision: prerendered static files, not server rendering.** Vercel serves the site as
static files, and every page but the passes is the same for every visitor. `outputMode:
"static"` writes the pages to HTML at build time and needs no server runtime; a per-request
server would cost a function invocation per page view to render text that never changes.
Passes are never prerendered: stale before anyone reads them.

**Decision: hydration.** The browser adopts the prerendered DOM instead of redrawing it,
which also means no flash between the static page and the app. Accepted cost: the initial
bundle grows by about 65 kB raw, 19 kB transferred (483 → 549 kB raw), past the 500 kB
warning budget, which moves to 600 kB. The error budget stays at 1 MB.

**Decision: one build per language, English at the root.** `@angular/localize` compiles the
translations in, so the French build ships no dictionary and no English. English stays at
the root so that no published address changes; French lives under `/fr/`. No automatic
redirect by browser language: it would hide the French pages from a crawler that sends
none.

**Decision: the public origin comes from the build environment** (`SITE_URL`, else
Vercel's `VERCEL_PROJECT_PRODUCTION_URL`), never from the code. Without one, canonical
links and the sitemap are omitted rather than wrong.

Not done here: a domain of the project's own, an Open Graph image, more prerendered
satellites, per-city pages. Verified: 165 frontend tests, the generators of robots.txt and
sitemap.xml, both builds rendered and hydrated in Chromium behind a local server that
mimics Vercel's routing. Not verified: the same on a real Vercel deployment.

---

## Milestone 19 — Natural-language queries (optional, cheap to try)

"When can I see the ISS from Lyon this weekend, if it needs to be high enough to photograph?"
→ satellite, site, window, minimum elevation. This is argument-filling from a sentence, which
is what the TypeSafe primitives installed in this environment are for (see
[CLAUDE.md](CLAUDE.md)); the pass computation itself stays entirely in Orekit, where it
belongs. A second, related judgment ranks a night's passes by how good they actually are —
elevation, duration, hour, illumination — which is a scoring problem, not an orbital one.

Worth building only if milestone 17 reports that people ask for it. Listed here so that the
idea is on the record with its justification, and so that it is not mistaken for the product.

---

## What would make this fail

Written down for the same reason as the accepted limitations below: to be defended, not
discovered.

- **Nobody pays for a pass API.** The most likely outcome. Milestone 17's kill criterion
  exists to detect it in three months rather than two years.
- **Building phase 2 before milestone 9.** An unfinished portfolio project and an unsold
  product is the worst of both. Finish the showcase pass first; it is 4 h and it is also
  the thing milestone 17 links to.
- **Free competitors adding an API.** N2YO already has one. The answer is not more features,
  it is the validation, the documentation and the support — which is exactly what a solo
  project can credibly offer and a free service cannot.
- **Support eating the 4 h/week.** Every hour answering mail is an hour not shipping. This
  is the real reason milestones 13, 14 and 16 insist on self-serve everything.
- **The data-rights question answered badly**, and answered late. See milestone 15.

---

# Phase 3 — Show what is new in orbit

## Why the direction changes (2 October 2026)

Presenting NextPass on Reddit drew the answer phase 2's premise already half-admitted:
orbit and pass predictors exist by the dozen, and the value lies in the *information drawn
from orbits*, not in the propagation. The suggested source of problems worth solving is the
list published by the US Space Force's SDA TAP Lab.

What a survey of that list found, for a solo developer:

- **Manoeuvre detection** is already well served (Orbital Radar, SkyOSINT, SatWatch,
  maneuver-detect). Rejected as a product.
- **On-orbit object separations** — a satellite releasing another object, a deployer
  emptying, a body breaking up — are the least served. Chosen.
- **A radio profile from SatNOGS** is interesting; its feasibility is unverified. Parked.

**[GCAT](https://planet4589.org/space/gcat/)**, Jonathan McDowell's catalogue, already
records separations: every object has a `Parent` and a separation date `SDate`, under
CC-BY. It is at once a partial competitor, the ground truth any home-made detector would be
measured against, and the fastest way to ship something.

## Positioning

Show what is new in orbit, explain it with evidence, and say when to see it pass overhead.
Two audiences, deliberately:

- **OSINT analysts and journalists**, who want to understand a separation: what released
  what, when, how sure the record is, where the claim comes from.
- **Observers**, who want to watch it go by — the pass predictor phases 1 and 2 built.

The pass predictor stops being the product and becomes the second half of every event page.

**Guardrails.** One data foundation (GCAT, then whatever phase 3.3 justifies). One feature
in progress at a time. Phase 2's billing stays disabled; nothing here is sold before
phase 3.2 says someone wants it.

## What GCAT holds, measured on 2 October 2026

- `satcat.tsv` (19 MB, 70 000 rows) stops at catalogue number 69999. Objects catalogued
  since July 2026 carry six-digit numbers and live in **`satcat100k.tsv`** (≈ 960 rows).
  An import that reads only `satcat.tsv` silently stops at 11 July 2026.
- It is fresh: the Falcon Heavy that lifted off at 03:54 UTC that morning was listed by 13:52.
  The files carry an `ETag` and a `Last-Modified`.
- `Parent` is the object this one was attached to — or, when it leaves a body's sphere of
  influence, that body's name. An object's prefix names its catalogue: `S` the standard
  catalogue (the US satellite catalogue), `A` the auxiliary one (objects the US catalogue
  omits, such as most Starlink upper stages), `L` low-altitude objects (apogee below
  80 km) and `R` suborbital ones (80 km or more), such as spent first stages. Most rows
  simply point at the stage that carried them. **Working definition: a separation is a
  row whose parent is an `S…` object and whose `SDate` is not its launch day** — 106 so
  far in 2026 across both files, from a Shenzhou orbital module to debris shed by a
  Yaogan satellite. Phase 3.1 refines it on real cases.
- Dates have variable precision and an uncertainty mark: `2026 May?`,
  `2026 Jun 19 2200?`, `2026 Jul 11 0402:25`. Both must survive the import; rounding
  `2026 May?` to 1 May would invent a fact.

## Phase 3.0 — Foundations: the GCAT import (done)

- Download `satcat.tsv` and `satcat100k.tsv`, conditionally on their `ETag`; stream-parse
  them (the free Render instance has 512 MB); upsert by `JCAT` so a rerun changes nothing.
- Store each date as an instant plus its precision and its uncertainty.
- `POST /internal/import`, triggered nightly by `.github/workflows/daily-import.yml`,
  reports rows read, objects added and separations added.
- Attribution to GCAT (CC-BY) wherever its data is shown.

*Done when* the nightly job fills the database and a second run reports zero changes.

*Implemented:* `space.nextpass.gcat`, migration `V5__gcat_objects.sql`. Columns are found
by header name; `Parent` keeps its identifier for joins and GCAT's whole cell (`S03504*`,
`S16273  AL`), whose mark and location phase 3.1 interprets. An apogee of `Inf` (escape)
is stored as infinity. `first_seen_at` is written once, for phase 3.2. Measured locally
against the live files with a 358 MB heap: 70 961 objects and 28 372 separations in
13 s, 256 MB resident, 23 MB of table; the next run answers `304` in 0.3 s; a forced
re-download writes nothing. `GcatImporterPostgresTest` covers the conditional download,
partial changes, `first_seen_at` and a download cut halfway. **In production on
2 October 2026:** the first run imported 70 961 objects in 53 s on the free Render instance
and Neon; the second answered `"status": "unchanged"` in 0.3 s.

## Phase 3.1 — Event pages from GCAT (implemented; deployment pending)

One page per separation: parent, child, date with its precision, orbit, what GCAT says and
where it says it — and a link to the child's next passes. A list of the latest ones.

*Implemented,* from the validated mockup: `GET /api/separations` (latest events, this
year by month) and `GET /api/separations/{id}`, outside the quota like the name search,
cached 15 minutes; `/separations` (prerendered text, live list and month chart, filter by
kind) and `/separations/:id` (rendered in the browser).

- **An event is a parent and a recorded date**: the rows sharing both. Sixty-two fragments
  of one breakup are one event. Its address is its smallest record identifier, and any
  member's identifier finds it, so a link survives a member being added. A release leaves
  at least one object that is not debris; a fragmentation leaves only debris. Records the
  catalogue marks `ERR` are left out.
- **Dates read at their recorded precision**: `2026 Sep?` is "September 2026 · to the
  month · uncertain", never the first of September; times are UTC and say so.
- **The page says why it shows no passes** rather than leaving a gap: no longer in orbit
  (a decay date), no catalogue number, no published elements (the passes endpoint answers
  `unknown-satellite`, as for USA 396 and USA 667), or a six-digit number the predictor
  does not accept yet. A breakup lists its fragments, each linked to its own page.
- **The source is credited once, at the foot of the page** (CC BY 4.0), not in the copy.

**Known gap, its own change:** the pass endpoint and the satellite pages accept NORAD
numbers up to 99999 only, so objects catalogued since July 2026 — most recent
separations — show no passes yet.

*Verified:* `SeparationRepositoryPostgresTest` (grouping, kinds, monthly counts, lineage,
evidence, a missing parent) on real GCAT rows; `SeparationControllerTest`; page tests for
both pages; the list and three event pages checked by hand against the live import.

## Phase 3.2 — Measure GCAT's delay, meet real users (the decision point)

How long between a separation and its appearance in GCAT, compared with Space-Track's
first publication? And do analysts, journalists and observers use the pages? The answers
decide whether phase 3.3 exists.

## Phase 3.3 — A home-made detector, only if it buys speed

Detecting separations from element sets is only worth the effort if it is faster than
GCAT. GCAT is the ground truth it is tested against.

## Phase 3.4 — Classification and alerts

Deployment, debris, breakup; RSS and an API for whoever wants to be told first.

---

# Accepted limitations

These choices are deliberate and are to be defended, not hidden. They belong to the whole
project, phase 1 and phase 2 alike:

- **SGP4 only.** The model drifts beyond a few days; the forecast window is therefore
  bounded. That is the right answer for TLEs, not a limitation suffered.
- **No atmospheric refraction** below 5° of elevation in the MVP.
- **No database** until a real need calls for one.
- **A single dark theme** in the frontend: the real use is at night. That is a choice, not
  saved effort.
- **The frontend computes no orbit.** Neither the sky chart nor the globe.
