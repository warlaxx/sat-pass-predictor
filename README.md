# Sat Pass Predictor

[![CI](https://github.com/warlaxx/sat-pass-predictor/actions/workflows/ci.yml/badge.svg)](https://github.com/warlaxx/sat-pass-predictor/actions/workflows/ci.yml)

Computing and visualising satellite passes over a given point on Earth.
Java / Spring Boot backend with [Orekit](https://www.orekit.org/), Angular frontend.

> A learning project aimed at the space ecosystem: SGP4 propagation from TLEs, reference
> frames and time scales, visibility event detection.

## Stack

| Piece     | Choice                                  |
|-----------|-----------------------------------------|
| Backend   | Java 25, Spring Boot 4.1.1, Maven       |
| Dynamics  | Orekit 13.1.8                           |
| Frontend  | Angular 22 (standalone, signals, SCSS)  |
| TLEs      | CelesTrak GP API                        |

## Prerequisites

- **JDK 25** — the project compiles with `release 25`; an older JDK fails with
  `release version 25 not supported`.

  ```bash
  brew install openjdk@25
  # Homebrew JDKs are keg-only: without this link, /usr/libexec/java_home cannot see them.
  sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk \
               /Library/Java/JavaVirtualMachines/openjdk-25.jdk
  export JAVA_HOME=$(/usr/libexec/java_home -v 25)
  ```

- **No Maven to install**: `backend/mvnw` downloads the pinned version (3.9.16) on
  first use. It is the command the CI runs, so what passes here passes there.
- **Node 22 LTS**

## Getting started

```bash
# 1. Orekit data (leap seconds, EOP, ephemerides) — ~100 MB, not committed
./scripts/fetch-orekit-data.sh

# 2. Backend (http://localhost:8080)
cd backend && ./mvnw spring-boot:run

# 3. Frontend (http://localhost:4200, /api proxied to 8080)
cd frontend && npm install && npm start
```

## Tests

```bash
cd backend  && ./mvnw verify
cd frontend && npm test
```

The cross-validation against Skyfield is a separate script, deliberately kept out of CI
(see [Validation](#validation)). It runs in its own virtual environment, so that it
depends neither on the machine's default `python` nor on a global installation:

```bash
python3 -m venv .venv-validation
.venv-validation/bin/pip install skyfield
.venv-validation/bin/python scripts/validate-against-skyfield.py
```

Skyfield requires Python 3. A `pip install` run under a still-active Python 2 — through
pyenv, for instance — fails while compiling `sgp4` with a `SyntaxError` in its `setup.py`:
the message points at sgp4, the cause is the interpreter. `python3 -m pip --version` says
which one is really in use.

## Orekit data

Orekit needs an external data set (UTC-TAI history, IERS Earth orientation parameters,
gravity models). Without it, the first call to `TimeScalesFactory.getUTC()` fails. Those
files are large and updated regularly: they are not committed, but downloaded by
`scripts/fetch-orekit-data.sh` and loaded at startup by `OrekitConfig`. The path is
configurable through `OREKIT_DATA_PATH`.

The application refuses to start if the directory is missing: an explicit failure at
startup beats an obscure error at the first computation.

## TLE sources

Orbital elements come from a **chain of sources, tried in order**, configured by
`tle.base-urls` and overridable in one go with `TLE_BASE_URLS` (comma-separated):

1. `https://celestrak.org` — the origin.
2. `https://sat-pass-predictor-nine.vercel.app/tle-upstream` — the same CelesTrak, reached
   through a rewrite on the frontend's host. It exists because CelesTrak silently drops
   packets coming from the shared outbound IPs of the platform the API is deployed on: a
   connect timeout, no refusal, no DNS error, on a host that answers other datacenters in
   10 ms. Reachability is not a property of a service alone.
3. **Space-Track**, optional, only when `SPACETRACK_IDENTITY` and `SPACETRACK_PASSWORD`
   are both set. Without them the application starts on the first two and says so in its
   startup log.

A source that says "I do not have this object" ends the chain; a source that cannot be
reached does not. Space-Track is an *availability* fallback, not a *coverage* one — it is
the catalogue CelesTrak republishes — and its calls are capped well under the published
limits of 30 a minute and 300 an hour, because an account suspended is a source lost.

The line to read in the startup log is `TLE sources, in order: [...]`. It says exactly
what this instance will try, which is the first thing worth knowing when the deployed
application and the local one disagree.

## Validation

An astrodynamics computation that is compared to nothing is not a computation, it is an
opinion. The project therefore rests on two distinct checks, which do not prove the same
thing and neither of which replaces the other.

**A single reference file.**
`backend/src/test/resources/validation/iss-lyon-reference.json` carries the TLE, the
observer, the window and the expected passes. It is read by the Java test *and* by the
Python script. Two files would have meant two truths, one of which could have lied
without a sound.

**Check 1 — regression (Java, in CI).**
`PassPredictionReferenceTest` verifies that Orekit reproduces the reference. It watches
for drift: a version bump, a refresh of the IERS data, a rewrite of the service. It says
nothing about correctness: a wrong computation would freeze a wrong reference, which this
test would then defend faithfully.

**Check 2 — correctness (Python, outside CI).**
`scripts/validate-against-skyfield.py` confronts the same reference with
[Skyfield](https://rhodesmill.org/skyfield/), an implementation of SGP4 written in Python,
sharing no code with Orekit.

The naive comparison — asking Skyfield for its passes and comparing dates — gives
discrepancies of up to a second, without saying which of the two is wrong: Skyfield's
`find_events` is documented as accurate to the second. The script therefore works the
other way round: it takes the dates produced by Orekit and asks Skyfield **what elevation
and azimuth it computes at those exact instants**. If Orekit is right, Skyfield must find
exactly the threshold at the boundaries of the pass.

Four checks: the boundaries, the culmination (its value and the fact that it is a local
maximum), the three azimuths, and completeness — the last one being the only check able to
detect a pass *missed* by Orekit's 60 s detection step.

### Tolerances, and why

| Quantity | Measured discrepancy | Tolerance | Margin |
|---|---|---|---|
| Elevation (Orekit vs Skyfield) | 0.53 millidegree | 10 millidegrees | x19 |
| Azimuth (Orekit vs Skyfield) | 2.0 millidegrees | 20 millidegrees | x10 |
| Dates (Java regression) | 0 | 1 s | — |
| Angles (Java regression) | 0 | 0.1 degree | — |

The regression tolerances are not physical error margins: they absorb an internal change
in Orekit, not a model error, which would be several orders of magnitude larger. Useful
landmarks: near AOS, the ISS gains about 0.1 degree of elevation per second — a
one-second discrepancy and a 0.1-degree discrepancy therefore describe the same event.

### What this validation does not prove

Skyfield and Orekit implement **the same model**, SGP4. Their agreement establishes that
this project's implementation and chain of frames are correct. It says nothing about the
gap to the real sky, which is dominated by the age of the TLE: in low Earth orbit, SGP4
drifts by roughly 1 to 3 km per day, more during a geomagnetic storm. That is a limitation
of the model, accepted, and the reason the forecast window is bounded to a few days.

A comparison against Heavens-Above would answer the other question. It was deliberately
set aside: its discrepancy mixes the TLE error, refraction and the site's own display
conventions, and would therefore not be interpretable.

### Why the Python script is not in CI

It would require Python and Skyfield in the workflow, to check a file that does not
change. The correctness of the reference is established once; it is its drift that must be
watched continuously, and the Java test takes care of that. The script is to be re-run by
hand whenever the reference changes — which is exactly what the comment at the top of the
JSON file asks for.

## Roadmap

Details, milestones and time budget: [ROADMAP.md](ROADMAP.md).

- [x] Orekit data loading, tested
- [x] Pass computation (`TLEPropagator` + `ElevationDetector`)
- [x] Cross-validation against an independent SGP4 implementation (Skyfield)
- [x] Track sampling (`track`, `OrekitStepHandler`, fixed 10 s step)
- [x] TLE retrieval from CelesTrak (last known TLE, age exposed)
- [x] REST API `/api/passes` (Problem Details, springdoc)
- [x] Frontend: shell, pass list, ribbon of nights, TLE age banner
- [ ] Polar sky chart (SVG, no dependency)
- [ ] 3D globe: ground track, visibility circle, terminator
- [ ] Docker Compose, showcase pass
- [ ] Naked-eye visible passes, TLE cache (PostgreSQL)

Validated interface mockup: [docs/interface-mockup.html](docs/interface-mockup.html)
(open it in a browser).

## How this repository was written

Part of the code in this repository was written with the assistance of Claude
(Anthropic): the commits concerned carry a `Co-Authored-By` trailer. Better said here
than left for the reader to discover in `git log`.

What that covers, concretely:

- Code and tests were written with assistance, then **run and confronted with an
  independent reference** before being committed. The [Validation](#validation) section
  describes the procedure; anyone can reproduce it with two commands.
- Non-trivial technical decisions are documented in the code, with their rationale and
  their trade-off: the 60 s detection step instead of the 600 s default, the
  `ContinueOnEvent` handler without which only one pass would be detected, the elevation
  maximum as a *decreasing* event of the derivative, the exclusion of passes truncated by
  the edges of the window.
- The limitations of the model are written down rather than passed over in silence: SGP4
  drift, no refraction below 5 degrees, the real reach of the validation.

A tool that writes code does not excuse you from being able to defend it. This section
exists so that the repository is judged on what it demonstrates, not on what it hides.
