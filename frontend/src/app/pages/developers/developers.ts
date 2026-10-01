import { ChangeDetectionStrategy, Component } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { CodeBlock } from '../../shared/code-block';
import { Reveal } from '../../motion/reveal';
import { ACCOUNT_URL, API_ORIGIN, BATCH_LIMITS, PLANS, SWAGGER_URL } from '../../shared/site';

/**
 * The developer guide, written as the landing page of the API (roadmap, milestone 17):
 * a call that works on the first screen, then the contract, then the limits.
 *
 * Everything here restates docs/api-access.md and the controllers' annotations. The
 * OpenAPI reference stays the exhaustive one; this page is the one a person reads first.
 */
@Component({
  selector: 'app-developers',
  imports: [RouterLink, DecimalPipe, CodeBlock, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './developers.html',
})
export class DevelopersPage {
  protected readonly accountUrl = ACCOUNT_URL;
  protected readonly swaggerUrl = SWAGGER_URL;
  protected readonly apiOrigin = API_ORIGIN;
  protected readonly batch = BATCH_LIMITS;
  protected readonly free = PLANS[0];

  protected readonly firstCall = `curl -H "X-API-Key: $NEXTPASS_API_KEY" \\
  '${API_ORIGIN}/v1/passes?noradId=25544&lat=45.7578&lon=4.832&alt=170'`;

  protected readonly response = `{
  "satellite": { "noradId": 25544, "name": "ISS (ZARYA)" },
  "tle": {
    "epoch": "2026-09-30T06:12:44.123Z",
    "ageSeconds": 21304,
    "source": "CelesTrak",
    "fetchedAt": "2026-09-30T09:40:02Z",
    "line1": "1 25544U 98067A ...",
    "line2": "2 25544  51.63 ..."
  },
  "observer": { "latitudeDeg": 45.7578, "longitudeDeg": 4.832, "altitudeM": 170 },
  "minElevationDeg": 10,
  "frequencyMhz": null,
  "computedAt": "2026-09-30T12:07:48Z",
  "passes": [
    {
      "aos":         { "instant": "2026-09-30T19:42:10.412Z", "azimuthDeg": 247.1, "elevationDeg": 10.0, ... },
      "culmination": { "instant": "2026-09-30T19:45:21.870Z", "azimuthDeg": 172.4, "elevationDeg": 48.6, ... },
      "los":         { "instant": "2026-09-30T19:48:33.095Z", "azimuthDeg": 97.9,  "elevationDeg": 10.0, ... },
      "durationSeconds": 382,
      "track": [ { "instant": "...", "azimuthDeg": 247.1, "elevationDeg": 10.0, "rangeKm": 1362.4,
                   "rangeRateKmS": -6.41, "dopplerHz": null, "subPoint": { ... },
                   "illuminated": true, "visible": true }, ... ]
    }
  ]
}`;

  protected readonly doppler = `curl -H "X-API-Key: $NEXTPASS_API_KEY" \\
  '${API_ORIGIN}/v1/passes?noradId=25544&lat=45.7578&lon=4.832&frequencyMhz=145.8'`;

  protected readonly batchCall = `curl -H "X-API-Key: $NEXTPASS_API_KEY" \\
  '${API_ORIGIN}/v1/passes/batch?noradId=25544,20580&site=45.7578,4.8320,170&site=-33.92,18.42&track=false'`;

  protected readonly javascript = `const url = new URL('${API_ORIGIN}/v1/passes');
url.search = new URLSearchParams({ noradId: '25544', lat: '45.7578', lon: '4.832' }).toString();

const response = await fetch(url, { headers: { 'X-API-Key': process.env.NEXTPASS_API_KEY } });
if (!response.ok) {
  const problem = await response.json();          // RFC 9457: branch on problem.type
  throw new Error(\`\${problem.title}: \${problem.detail}\`);
}
const { passes } = await response.json();
for (const pass of passes) console.log(pass.aos.instant, pass.culmination.elevationDeg);`;

  protected readonly python = `import os, requests

response = requests.get(
    "${API_ORIGIN}/v1/passes",
    params={"noradId": 25544, "lat": 45.7578, "lon": 4.832},
    headers={"X-API-Key": os.environ["NEXTPASS_API_KEY"]},
    timeout=60,
)
if not response.ok:
    problem = response.json()                      # RFC 9457: branch on problem["type"]
    raise RuntimeError(f"{problem['title']}: {problem.get('detail')}")
for p in response.json()["passes"]:
    print(p["aos"]["instant"], p["culmination"]["elevationDeg"])`;

  protected readonly parameters = [
    { name: 'noradId', format: $localize`integer, 1–99999`, fallback: $localize`required`, meaning: $localize`NORAD catalogue number of the satellite.` },
    { name: 'lat', format: $localize`degrees, −90 to 90`, fallback: $localize`required`, meaning: $localize`Latitude of the observer (WGS84).` },
    { name: 'lon', format: $localize`degrees, −180 to 180`, fallback: $localize`required`, meaning: $localize`Longitude of the observer.` },
    { name: 'alt', format: $localize`metres, −500 to 9000`, fallback: '0', meaning: $localize`Altitude above the WGS84 ellipsoid, not above sea level.` },
    { name: 'hours', format: $localize`integer, 1–240`, fallback: '48', meaning: $localize`Length of the window, starting at the request.` },
    { name: 'minElevation', format: $localize`degrees, 0–89`, fallback: '10', meaning: $localize`Elevation a pass must exceed to count; AOS and LOS sit on it.` },
    { name: 'frequencyMhz', format: $localize`MHz, 1–300000`, fallback: $localize`none`, meaning: $localize`Downlink carrier; adds dopplerHz to every point and phase.` },
  ];

  protected readonly errors = [
    { status: 400, type: 'invalid-request', meaning: $localize`A parameter is missing, malformed or out of bounds. Not retryable as is.` },
    { status: 400, type: 'batch-exceeds-rate-limit', meaning: $localize`A batch larger than the key’s per-minute limit: it could never be admitted.` },
    { status: 401, type: 'invalid-api-key', meaning: $localize`Missing, unknown or revoked X-API-Key.` },
    { status: 404, type: 'unknown-satellite', meaning: $localize`The number is absent from CelesTrak: most likely a re-entered object.` },
    { status: 429, type: 'rate-limit-exceeded · daily-quota-exceeded · monthly-quota-exceeded', meaning: $localize`Limit reached. Retry-After and resetsAt say when; limit says which.` },
    { status: 503, type: 'tle-unavailable', meaning: $localize`No source of elements answered and none is held. Retry in about 15 s.` },
    { status: 503, type: 'tle-stale', meaning: $localize`The only elements held are older than 7 days: refused rather than shown.` },
    { status: 503, type: 'api-access-unavailable', meaning: $localize`Quota accounting is unavailable. The API fails closed; retry later.` },
  ];
}
