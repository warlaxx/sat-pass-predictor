import { describe, it, expect } from 'vitest';
import { PassDto, PassesResponse, TrackPointDto } from './passes.model';

/**
 * The API contract, pinned from the browser's side.
 *
 * These are the exact bytes `PassControllerTest` makes the backend produce - same
 * satellite, same TLE lines, same instants, same figures. The two suites pin the two
 * ends of one contract: Java asserts that the server serves this shape, TypeScript
 * asserts that the frontend reads it. A field renamed on one side breaks a test on that
 * side and leaves the other one honest.
 *
 * This is the price of hand-written types instead of generated ones. It is paid here,
 * once, in one file - and it buys types a human wrote for a human.
 */
const CAPTURED_RESPONSE = `
{
  "satellite": { "noradId": 25544, "name": "ISS (ZARYA)" },
  "tle": {
    "epoch": "2026-09-15T04:12:33Z",
    "ageSeconds": 121200,
    "source": "celestrak",
    "fetchedAt": "2026-09-16T11:25:04Z",
    "line1": "1 25544U 98067A   21035.14486477  .00001026  00000-0  26816-4 0  9998",
    "line2": "2 25544  51.6455 280.7636 0002243 335.6496 186.1723 15.48938788267977"
  },
  "observer": { "latitudeDeg": 45.7578, "longitudeDeg": 4.832, "altitudeM": 170.0 },
  "minElevationDeg": 10.0,
  "computedAt": "2026-09-16T13:52:33Z",
  "passes": [
    {
      "aos": { "instant": "2026-09-22T19:18:54Z", "azimuthDeg": 292.5, "elevationDeg": 10.0, "rangeKm": 1553.2 },
      "culmination": { "instant": "2026-09-22T19:22:16Z", "azimuthDeg": 22.5, "elevationDeg": 63.1, "rangeKm": 462.7 },
      "los": { "instant": "2026-09-22T19:25:38Z", "azimuthDeg": 112.4, "elevationDeg": 10.0, "rangeKm": 1551.8 },
      "durationSeconds": 404,
      "track": [
        { "instant": "2026-09-22T19:18:54Z", "azimuthDeg": 292.5, "elevationDeg": 10.0, "rangeKm": 1553.2,
          "subPoint": { "latitudeDeg": 38.71, "longitudeDeg": -4.92, "altitudeKm": 419.6 }, "illuminated": false },
        { "instant": "2026-09-22T19:22:16Z", "azimuthDeg": 22.5, "elevationDeg": 63.1, "rangeKm": 462.7,
          "subPoint": { "latitudeDeg": 44.02, "longitudeDeg": 3.11, "altitudeKm": 421.3 }, "illuminated": false },
        { "instant": "2026-09-22T19:25:38Z", "azimuthDeg": 112.4, "elevationDeg": 10.0, "rangeKm": 1551.8,
          "subPoint": { "latitudeDeg": 48.90, "longitudeDeg": 12.40, "altitudeKm": 423.0 }, "illuminated": false }
      ]
    }
  ]
}`;

const response = JSON.parse(CAPTURED_RESPONSE) as PassesResponse;

/** Comparing key sets, not just reading fields: a renamed field has to fail, not go unread. */
function keys(value: object): string[] {
  return Object.keys(value).sort();
}

describe('the /api/passes contract', () => {
  it('carries exactly the documented top-level fields', () => {
    expect(keys(response)).toEqual([
      'computedAt', 'minElevationDeg', 'observer', 'passes', 'satellite', 'tle',
    ]);
  });

  it('carries the TLE that actually served the computation, with its age', () => {
    expect(keys(response.tle)).toEqual([
      'ageSeconds', 'epoch', 'fetchedAt', 'line1', 'line2', 'source',
    ]);
    // The age is the server's, not a subtraction done here against a drifting clock.
    expect(response.tle.ageSeconds).toBe(121_200);
    expect(response.tle.line1.startsWith('1 25544U')).toBe(true);
  });

  it('gives each pass three phases and a track', () => {
    const pass: PassDto = response.passes[0];
    expect(keys(pass)).toEqual(['aos', 'culmination', 'durationSeconds', 'los', 'track']);
    expect(keys(pass.aos)).toEqual(['azimuthDeg', 'elevationDeg', 'instant', 'rangeKm']);
  });

  /**
   * The milestone 3 invariant, seen from the browser. The sky chart will draw the
   * culmination marker on the curve because of it; if it ever stopped holding, the
   * marker would float beside the track and nobody would know why.
   */
  it('starts the track at AOS and ends it at LOS', () => {
    const pass = response.passes[0];
    expect(pass.track[0].instant).toBe(pass.aos.instant);
    expect(pass.track[pass.track.length - 1].instant).toBe(pass.los.instant);
    expect(pass.track.some((point) => point.instant === pass.culmination.instant)).toBe(true);
  });

  it('gives every track point a sub-satellite position and an illumination flag', () => {
    const point: TrackPointDto = response.passes[0].track[1];
    expect(keys(point)).toEqual([
      'azimuthDeg', 'elevationDeg', 'illuminated', 'instant', 'rangeKm', 'subPoint',
    ]);
    expect(keys(point.subPoint)).toEqual(['altitudeKm', 'latitudeDeg', 'longitudeDeg']);
    // False until milestone 10. The field exists so that the globe need not change then.
    expect(point.illuminated).toBe(false);
  });
});
