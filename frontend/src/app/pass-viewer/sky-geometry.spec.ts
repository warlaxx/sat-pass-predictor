import { describe, expect, it } from 'vitest';
import { project, sampleAt } from './sky-geometry';

const point = (instant: string, azimuthDeg: number, elevationDeg = 10) => ({ instant, azimuthDeg, elevationDeg, rangeKm: 1000 });
describe('sky geometry', () => {
  it('places zenith at centre and cardinal horizons on their axes', () => {
    expect(project({ azimuthDeg: 123, elevationDeg: 90 })).toEqual({ x: 180, y: 180 });
    expect(project({ azimuthDeg: 0, elevationDeg: 0 })).toEqual({ x: 180, y: 36 });
    expect(project({ azimuthDeg: 90, elevationDeg: 0 }).x).toBeCloseTo(324);
    expect(project({ azimuthDeg: 270, elevationDeg: 0 }).x).toBeCloseTo(36);
  });
  it('crosses north without jumping through south', () => {
    const track = [point('2026-09-19T00:00:00Z', 359), point('2026-09-19T00:00:10Z', 1, 20)];
    const result = sampleAt(track, Date.parse('2026-09-19T00:00:05Z'))!;
    expect(result.azimuthDeg).toBe(0);
    expect(result.elevationDeg).toBe(15);
    expect(sampleAt(track, 0)).toBe(track[0]);
    expect(sampleAt(track, Infinity)).toBe(track[1]);
  });
  it('uses actual timestamps for uneven samples and preserves exact culmination', () => {
    const track = [point('2026-09-19T00:00:00Z', 0), point('2026-09-19T00:00:03Z', 30, 80), point('2026-09-19T00:00:10Z', 60)];
    expect(sampleAt(track, Date.parse(track[1].instant))?.elevationDeg).toBe(80);
    expect(sampleAt([], 0)).toBeUndefined();
  });
});
