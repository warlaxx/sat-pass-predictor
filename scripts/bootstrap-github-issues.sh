#!/usr/bin/env bash
# Creates the roadmap's milestones and issues on GitHub.
#
# Prerequisite: gh installed and authenticated (gh auth login).
# Not idempotent: running it again would create duplicates, run it once only.
#
# NOTE: this script was run once, before milestones 3 and 4 were reordered. Its
# numbering no longer matches ROADMAP.md, which is the single source of truth. Keep it as
# a record of how the board was seeded, not as something to run again.
set -euo pipefail

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
echo "Repository: $REPO"

milestone() { # $1 title, $2 description, $3 due (YYYY-MM-DD)
  gh api "repos/$REPO/milestones" -f title="$1" -f description="$2" \
     -f due_on="${3}T23:59:59Z" -q .number
}

M1=$(milestone "Milestone 1 — Computing a pass" "Orekit core: TLEPropagator + ElevationDetector" 2026-10-04)
M2=$(milestone "Milestone 2 — Cross-validation" "Comparison against an external reference" 2026-10-11)
M3=$(milestone "Milestone 3 — TLE retrieval" "CelesTrak client + in-memory store" 2026-10-18)
M4=$(milestone "Milestone 4 — REST API" "/api/passes endpoint + OpenAPI" 2026-10-25)
M5=$(milestone "Milestone 5 — Frontend" "Form and pass table" 2026-11-05)
M6=$(milestone "Milestone 6 — Visualisation" "Polar chart of the pass" 2026-11-15)
M7=$(milestone "Milestone 7 — Showcase pass" "Docker, README, demo GIF" 2026-11-22)
M8=$(milestone "Milestone 8 — Differentiation" "Visible passes, PostgreSQL cache" 2026-12-20)

for L in "orekit:0E8A16:Flight dynamics and computation" \
         "backend:1D76DB:Spring Boot" \
         "frontend:5319E7:Angular" \
         "infra:B60205:CI, Docker, tooling" \
         "doc:FBCA04:README and documentation"; do
  IFS=: read -r name color desc <<< "$L"
  gh label create "$name" --color "$color" --description "$desc" --force >/dev/null
done

issue() { # $1 milestone, $2 labels, $3 title, $4 body
  gh issue create --milestone "$1" --label "$2" --title "$3" --body "$4" >/dev/null
  echo "  + $3"
}

issue "$M1" orekit "Build the TopocentricFrame of the observation site" \
"OneAxisEllipsoid WGS84 on ITRF + GeodeticPoint (latitude, longitude, altitude) -> TopocentricFrame.

Exit criterion: a test checking that the site position reprojected to geodetic gives back the input coordinates to within one metre."

issue "$M1" orekit "Propagate an ISS TLE with TLEPropagator" \
"TLE hard-coded in a test fixture, no network call.

Must be explainable: a TLE is expressed in the TEME frame. Document the conversion in the code."

issue "$M1" orekit "Detect passes with ElevationDetector + EventsLogger" \
"Elevation threshold 10 degrees, 24 h window.

Exit criterion: a list of ordered, disjoint passes, with AOS, LOS, duration, maximum elevation and azimuths."

issue "$M2" "orekit" "Freeze a validation fixture (TLE + date)" \
"Pick a stable dated TLE and observation date, commit them as a test resource."

issue "$M2" "orekit,doc" "Compare the results against an external reference" \
"Source: Heavens-Above or an independent computation (Skyfield).

Exit criterion: tolerances chosen AND justified in the test and the README (e.g. +/- 30 s on AOS)."

issue "$M3" backend "RestClient client to the CelesTrak GP API" \
"Retrieval by NORAD CATNR. Timeout, unknown satellite and unavailable service handled explicitly."

issue "$M3" backend "In-memory TLE store (Caffeine, 2 h refresh)" \
"CelesTrak asks callers not to poll its API in a loop. No database at this stage."

issue "$M3" backend "Client tests with MockRestServiceServer" \
"No real network call in CI."

issue "$M4" backend "GET /api/passes endpoint" \
"Parameters: noradId, lat, lon, altitude, hours, minElevation.
DTOs in ISO-8601 UTC Instant: time zones are a display problem, not a computation one."

issue "$M4" backend "Jakarta validation and RFC 9457 error handling" \
"@RestControllerAdvice, responses in the Problem Details format."

issue "$M4" "backend,doc" "OpenAPI documentation (springdoc)" \
"Expose /swagger-ui and link the page from the README."

issue "$M5" frontend "Search form (signals)" \
"Satellite, position, window, minimum elevation. Browser geolocation optional."

issue "$M5" frontend "Pass table" \
"Local time, duration, maximum elevation, AOS/LOS azimuths. Loading and error states handled."

issue "$M6" frontend "SVG polar chart of the track" \
"Azimuth / elevation. Serves as the demo image in the README."

issue "$M7" infra "Multi-stage Dockerfile and docker-compose" \
"orekit-data downloaded at build time, not at runtime."

issue "$M7" doc "README: demo GIF, architecture diagram, CI badge" \
""

issue "$M7" doc "Physical model section of the README" \
"TEME / GCRF / ITRF frames, UTC / TAI / UT1 time scales, limitations of SGP4 and validity window."

issue "$M8" orekit "Naked-eye visible passes (illumination and eclipse)" \
"Satellite lit by the Sun AND observer in the dark.
This is the feature that sets this repository apart from a generic tracker."

issue "$M8" backend "TLE cache in PostgreSQL" \
"Only when the need is real: persistence across restarts, history."

echo "Done."
