import { describe, expect, it } from 'vitest';
import { TrackPointDto } from '../api/passes.model';
import {
  destinationPoint, isDaylit, sampleSubPointAt, subsolarPoint, toUnitVector, visibilityRadiusDeg,
} from './globe-geometry';

const point = (seconds: number, latitudeDeg: number, longitudeDeg: number, altitudeKm = 420): TrackPointDto => ({
  instant: new Date(Date.UTC(2026, 8, 19, 20, 0, seconds)).toISOString(),
  azimuthDeg: 0, elevationDeg: 10, rangeKm: 1000, illuminated: false,
  subPoint: { latitudeDeg, longitudeDeg, altitudeKm },
});

describe('toUnitVector', () => {
  it('places the equator/prime-meridian point on the z axis and the pole on y', () => {
    expect(toUnitVector({ latitudeDeg: 0, longitudeDeg: 0 })).toEqual({ x: 0, y: 0, z: 1 });
    const pole = toUnitVector({ latitudeDeg: 90, longitudeDeg: 30 });
    expect(pole.y).toBeCloseTo(1);
    expect(pole.x).toBeCloseTo(0);
  });
});

describe('subsolarPoint', () => {
  it('sits near the equator at the equinox and moves west through the day', () => {
    const equinox = subsolarPoint(Date.parse('2026-09-23T02:04:00Z'));
    expect(equinox.latitudeDeg).toBeCloseTo(0, 0);
    // A low-precision formula for a lighting direction, not an ephemeris: it is only
    // asked to land within a couple of degrees, which the equation of time alone can cost.
    const noon = subsolarPoint(Date.parse('2026-09-19T12:00:00Z'));
    expect(Math.abs(noon.longitudeDeg)).toBeLessThan(2);
    const sixHoursLater = subsolarPoint(Date.parse('2026-09-19T18:00:00Z'));
    expect(sixHoursLater.longitudeDeg).toBeCloseTo(-90, -1);
  });
});

describe('isDaylit', () => {
  it('is true under the subsolar point and false on the opposite side of the globe', () => {
    const sun = { latitudeDeg: 10, longitudeDeg: 20 };
    expect(isDaylit(sun, sun)).toBe(true);
    expect(isDaylit({ latitudeDeg: -10, longitudeDeg: -160 }, sun)).toBe(false);
  });
});

describe('destinationPoint', () => {
  it('travels a quarter of the globe north and lands on the pole regardless of bearing', () => {
    const pole = destinationPoint({ latitudeDeg: 0, longitudeDeg: 12 }, 0, 90);
    expect(pole.latitudeDeg).toBeCloseTo(90);
  });
  it('round-trips due north then due south back to the start', () => {
    // A great circle's bearing drifts along the way, except along a meridian: reversing
    // the *initial* bearing by 180 degrees only returns to the start for a due north/south
    // leg, which is why this check (unlike an arbitrary bearing) can be exact.
    const start = { latitudeDeg: 20, longitudeDeg: 50 };
    const north = destinationPoint(start, 0, 15);
    const back = destinationPoint(north, 180, 15);
    expect(back.latitudeDeg).toBeCloseTo(start.latitudeDeg, 6);
    expect(back.longitudeDeg).toBeCloseTo(start.longitudeDeg, 6);
  });
});

describe('visibilityRadiusDeg', () => {
  it('matches the documented ISS figure: about 12.5 degrees for a 10-degree threshold', () => {
    expect(visibilityRadiusDeg(420, 10)).toBeCloseTo(12.5, 1);
  });
  it('shrinks as the elevation threshold rises', () => {
    expect(visibilityRadiusDeg(420, 30)).toBeLessThan(visibilityRadiusDeg(420, 10));
  });
});

describe('sampleSubPointAt', () => {
  it('interpolates latitude, longitude and altitude between samples', () => {
    const track = [point(0, 40, 10, 400), point(10, 42, 14, 410)];
    const middle = sampleSubPointAt(track, Date.parse(track[0].instant) + 5000)!;
    expect(middle.latitudeDeg).toBeCloseTo(41);
    expect(middle.longitudeDeg).toBeCloseTo(12);
    expect(middle.altitudeKm).toBeCloseTo(405);
  });
  it('crosses the antimeridian the short way', () => {
    const track = [point(0, 0, 179), point(10, 0, -179)];
    const middle = sampleSubPointAt(track, Date.parse(track[0].instant) + 5000)!;
    expect(Math.abs(middle.longitudeDeg)).toBeCloseTo(180);
  });
  it('clamps to the first and last samples outside the track window', () => {
    const track = [point(0, 40, 10), point(10, 42, 14)];
    expect(sampleSubPointAt(track, 0)).toEqual(track[0].subPoint);
    expect(sampleSubPointAt(track, Infinity)).toEqual(track[1].subPoint);
    expect(sampleSubPointAt([], 0)).toBeUndefined();
  });
});
