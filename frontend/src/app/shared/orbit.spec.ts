import { describe, expect, it } from 'vitest';
import { orbitFromTle, orbitRegime } from './orbit';

// ISS elements of the validation reference (backend/src/test/resources/validation).
const ISS_LINE_2 = '2 25544  51.6455 280.7636 0002243 335.6496 186.1723 15.48938788267977';

describe('orbitFromTle', () => {
  it('reads inclination, eccentricity and mean motion from their columns', () => {
    const orbit = orbitFromTle(ISS_LINE_2)!;
    expect(orbit.inclinationDeg).toBeCloseTo(51.6455, 4);
    expect(orbit.eccentricity).toBeCloseTo(0.0002243, 7);
    expect(orbit.revolutionsPerDay).toBeCloseTo(15.48938788, 8);
    expect(orbit.revolutionNumber).toBe(26797);
  });

  it('derives a period and altitudes a reader recognises for the ISS', () => {
    const orbit = orbitFromTle(ISS_LINE_2)!;
    expect(orbit.periodMinutes).toBeCloseTo(92.97, 1);
    // Kepler on the mean motion: right to a few kilometres, which is all a summary claims.
    expect(orbit.perigeeKm).toBeGreaterThan(410);
    expect(orbit.apogeeKm).toBeLessThan(430);
    expect(orbitRegime(orbit)).toBe('Low Earth orbit');
  });

  it('refuses a line that is not a line 2', () => {
    expect(orbitFromTle('1 25544U 98067A   08264.51782528 -.00002182  00000-0 -11606-4 0  2927')).toBeUndefined();
    expect(orbitFromTle('2 25544')).toBeUndefined();
  });
});
