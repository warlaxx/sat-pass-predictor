# Roadmap

Real budget: **~4 h/week**. Every milestone is designed to fit in 1 to 2 weeks, to be
pushed on its own and to be demonstrable on its own. No milestone depends on a future one
to make sense: if the project stops at milestone 4, what is online stays coherent.

Target: a presentable version by **end of November 2026**, mid-December with slack.

> Milestones 0 to 5 are done. About 21 h remain, that is a little over 5 weeks at
> 4 h/week — the whole frontend, and nothing else. Adding the 3D globe and splitting the
> frontend more finely cost about ten hours more than the initial roadmap. That is an
> accepted cost, not a slip: better written down than discovered in December.
>
> The backend is finished, apart from milestone 10. Everything that remains is what the
> project shows rather than what it computes.

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

**Two traps of the GP API**, both encoded in the tests: an unknown NORAD number answers
**200 with the body `No GP data found`**, not 404; and under load CelesTrak serves an HTML
page, still in 200. Any body that does not look like a TLE is therefore treated as an
outage. The returned number is also checked against the requested one: without that check,
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
`No GP data found`, an empty body, an HTML page, a wrong NORAD number, an altered
checksum, a timeout and a 500. `TleStoreTest` covers the refresh window, the backoff after
a failure, falling back to the last known TLE, the absence of a fallback on the first
call, forgetting a satellite removed from the catalogue, the hard age limit and the
collapsing of concurrent calls.

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

## Milestone 7 — Sky chart (≈ 5 h · 1.5 weeks)

The view that answers "where do I look up from Lyon". **No external dependency**: SVG
rendered by the Angular template from `computed()`.

- Polar disc: edge = horizon, centre = zenith, north at the top. Circles at 30° and 60°, a
  dashed circle at the 10° threshold, compass points outside.
- Track drawn from `track`: solid while the satellite is lit, dashed after that. Tick
  marks and time labels every minute.
- `TransportBarComponent`: a **single clock** for the whole page, `t ∈ [0, 2]` carried by
  a signal, play / pause / scrubber, and a continuous readout of time, azimuth, elevation,
  range and illumination state.
- `requestAnimationFrame`, never `setInterval`. Honour `prefers-reduced-motion`.
- Accessible text equivalent: the pass table, with a `<caption>` that says so.

This is the image that serves as the demo GIF in the README. It is worth more than three
paragraphs of description.

---

## Milestone 8 — 3D globe (≈ 6 h · 2 weeks)

The view that answers "where is the ISS, and who else can see it". Complementary to the
sky chart: one is in a topocentric frame, the other in a terrestrial frame.

**Non-negotiable condition: the globe computes nothing.** It renders what the API returns.
No propagation in the browser, no `satellite.js` — otherwise the question "which side does
the authoritative computation?" ruins the whole project.

- three.js (r128, UMD). Sphere, Natural Earth 110 m coastline, graticule, halo.
- Lighting by a `DirectionalLight` pointing along the Sun direction → the **day/night
  terminator** appears on its own, and visually explains why the pass is visible.
- Ground track from `subPoint`, split into a lit portion and a shadowed portion.
- **Visibility circle** around the sub-satellite point:
  `acos(Re/(Re+h)·cos(10°)) − 10°` ≈ 12.5°, about 1390 km.
- Observer marker, line of sight during the pass, mouse rotation (pointer events —
  `OrbitControls` is not in the UMD bundle).
- **Mandatory fallback** if three.js fails to load: an explicit message in the frame, and
  the rest of the page stays usable. That is precisely why the sky chart has no dependency.

---

## Milestone 9 — Showcase pass (≈ 4 h · 1 week)

- Multi-stage Dockerfile + `docker-compose.yml` (`orekit-data` downloaded at build time,
  not at runtime).
- README: demo GIF, architecture diagram, CI badge.
- "Physical model" section: frames (TEME / GCRF / ITRF), time scales (UTC / TAI / UT1),
  limitations of SGP4, role of the EOP.

---

## Milestone 10 — Differentiation (optional, but this is where the value is)

- **Naked-eye visible passes**: satellite lit by the Sun while the observer is in the
  dark. Requires the Sun's position and eclipse detection. This is the feature that turns
  "yet another tracker" into "someone who understood the dynamics".
  The `illuminated` field has existed since milestone 3; here it stops being `false`, and
  both views light up without changing a line of frontend code.
- TLE cache in PostgreSQL — at this stage only, when the need is real.
- Several satellites, next favourable window over 7 days.

---

## Accepted limitations

These choices are deliberate and are to be defended, not hidden:

- **SGP4 only.** The model drifts beyond a few days; the forecast window is therefore
  bounded. That is the right answer for TLEs, not a limitation suffered.
- **No atmospheric refraction** below 5° of elevation in the MVP.
- **No database** until a real need calls for one.
- **A single dark theme** in the frontend: the real use is at night. That is a choice, not
  saved effort.
- **The frontend computes no orbit.** Neither the sky chart nor the globe.
