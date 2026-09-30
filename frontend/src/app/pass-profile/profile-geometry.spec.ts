import { describe, it, expect } from 'vitest';
import { closestApproach, runsOf, symmetricBound, timeTicks } from './profile-geometry';

const at = (minutes: number) => Date.UTC(2026, 8, 22, 19, 0) + minutes * 60_000;

describe('timeTicks', () => {
  it('labels a short pass every minute, on round minutes', () => {
    const ticks = timeTicks(at(0.5), at(6.2));
    expect(ticks).toEqual([1, 2, 3, 4, 5, 6].map(at));
  });

  it('widens the step so a long window never gets more labels than asked for', () => {
    const ticks = timeTicks(at(0), at(48 * 60), 8);
    expect(ticks.length).toBeLessThanOrEqual(8);
    expect(ticks[1] - ticks[0]).toBe(6 * 3600_000);
  });

  it('draws nothing for an empty span', () => {
    expect(timeTicks(at(1), at(1))).toEqual([]);
  });
});

describe('closestApproach', () => {
  const sample = (seconds: number, rangeKm: number, rangeRateKmS: number) =>
    ({ instant: new Date(at(0) + seconds * 1000).toISOString(), rangeKm, rangeRateKmS });

  it('interpolates the instant the range rate crosses zero', () => {
    const found = closestApproach([sample(0, 900, -6), sample(10, 500, -2), sample(20, 520, 2), sample(30, 900, 6)]);
    expect(found?.instant).toBe(at(0) + 15_000);
    expect(found?.rangeKm).toBe(500);
  });

  it('does not invent a crossing for a satellite that only recedes', () => {
    expect(closestApproach([sample(0, 500, 1), sample(10, 600, 2)])).toBeUndefined();
  });
});

describe('symmetricBound', () => {
  it('rounds the largest magnitude up to a readable figure', () => {
    expect(symmetricBound([-3421, 3300])).toBe(4000);
    expect(symmetricBound([6.21, -6.2])).toBe(8);
    expect(symmetricBound([0.9])).toBe(1);
    expect(symmetricBound([0.14])).toBe(0.15);
  });

  it('never divides by zero', () => {
    expect(symmetricBound([0, 0])).toBe(1);
  });
});

describe('runsOf', () => {
  it('splits on a change of kind, each run ending on the first point of the next', () => {
    const runs = runsOf([1, 1, 2, 2, 1], value => value);
    expect(runs.map(run => [run.kind, run.points])).toEqual([[1, [1, 1, 2]], [2, [2, 2, 1]], [1, [1]]]);
  });
});
