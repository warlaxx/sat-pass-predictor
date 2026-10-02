# Manoeuvre detection — exploration notes, 2 October 2026

Status: **idea under evaluation, not scheduled.** Nothing here is implemented. It records
why the direction came up, what it would reuse, and what has to be true before any claim
is published. It does not replace the roadmap's phase 2 (see the last section).

## Why this came up

Feedback on Reddit, summarised:

1. Orbit-propagation globes are already plentiful.
2. The value lies in the **information extracted** from orbits, not in the prediction.
3. Professional operators already predict orbits and run basic conjunction assessment.
4. The project needs a **differentiator**; the commenter suggested the problem statements
   of the **SDA TAP Lab** as a source of ideas.

The SDA TAP Lab (*Space Domain Awareness Tools, Applications and Processing Lab*) is a US
Space Force programme that publishes concrete space-surveillance problems. It is a US
defence programme for companies: it is used here **only as a source of ideas**, not as
something to apply to.

### Problem statements considered

- **#11** — detect separation events automatically and classify them as a sub-satellite
  deployment or a debris-generating event. Feasible from TLE history.
- **#16, simplified** — manoeuvre detection from anomalous jumps in orbital elements.
- **#20** — a satellite's RF pattern of life. A possible lead through SatNOGS open data.
- **Set aside** — #1 (launch detection from imagery) and #3 (detection from seismic or
  weather data): out of reach for one person.

## Chosen direction: a manoeuvre detector

- It reuses the existing Orekit backend.
- The data is free: GP history from **Space-Track** (free account required).
- The result is demonstrable: "satellite X manoeuvred between these two epochs, here is the
  evidence".

### Main risk

TLEs are noisy. A jump in the elements can come from a poor TLE fit rather than a real
manoeuvre. **Separating fit noise from a real manoeuvre is the actual work**, and it is
what would make the project different.

### Mandatory validation

No claim before the method has been tested on **known manoeuvres**: ISS reboosts
(NORAD 25544), whose dates are public.

## Minimal first version

1. Fetch a satellite's TLE history over a given period.
2. Track the key elements (semi-major axis, inclination, …) over time.
3. Flag jumps outside the usual noise.
4. Compare the detections with the ISS's known manoeuvres.

## Review: what the plan above leaves out

Written down so that these are decided, not discovered.

- **Score false positives, not only hits.** Finding the ISS reboosts proves recall. The
  claim "satellite X manoeuvred" rests on precision: how many flags over a year match no
  known event. Freeze a labelled window (ISS events from NASA's published schedule over 6
  to 12 months), report both numbers, and choose the threshold on one period and measure
  on another.
- **The ISS is the easy case.** Large Δv, frequent TLE updates, public dates. A small
  station-keeping burn on a GEO or a Starlink-sized satellite is a different signal-to-noise
  problem. Say which regimes the detector is validated for; do not generalise from LEO.
- **Detrend before thresholding.** In low Earth orbit the semi-major axis falls every day
  from drag, faster during geomagnetic storms. A raw jump threshold fires on storms. Model
  the decay (or use `ndot`/`B*`) and threshold the residual.
- **Prefer prediction residuals to element differences.** TLE elements are SGP4 *mean*
  elements, tied to the model. The standard check is to propagate TLE *n* to the epoch of
  TLE *n+1* with SGP4 and measure the position difference, mostly along-track. It uses
  exactly the propagator already validated against Skyfield (README, "Validation") and is
  easier to explain than a jump in `a`.
- **The date is an interval, not a day.** A manoeuvre can only be placed between two TLE
  epochs. With sparse TLEs, "on day D" overstates the evidence.
- **CelesTrak does not replace the history.** CelesTrak serves current elements; the
  history comes from Space-Track (`gp_history`), which has its own query rate limits. Cache
  history locally and fetch it once.
- **Publishing is a data-rights question.** Milestone 15 already flags that Space-Track's
  user agreement constrains redistribution. A public feed of "events" derived from its
  history is more exposed than a pass prediction. Read the agreement before publishing
  results, not after.

### Facts the original notes got wrong

The notes listed Doppler and search by name as missing. Both shipped: range rate and an
optional Doppler shift on every track point (milestone 18), and name search through
`GET /api/satellites?q=…` (README, "Searching by name"). Still missing: magnitude and
alerts (alerts are listed under milestone 18 as demand-driven).

## Relation to the roadmap

Phase 2 bets on selling a pass API, with milestones 15 to 17 next and a 4 h/week budget.
A manoeuvre detector is a different product with a different audience. Before writing
code, decide which one it is:

- **A portfolio piece** that shows analysis beyond prediction — then cap it (one
  satellite, one validated method, one write-up) so that it does not stall milestone 15;
- **or a replacement for phase 2** — then say so in the roadmap and stop work on billing
  activation, rather than running two products at 2 h/week each.

## Open follow-up

A thank-you reply on Reddit also asked the commenter which problem statements suit a solo
developer. **Watch for the answer**: it can save a lot of time.
