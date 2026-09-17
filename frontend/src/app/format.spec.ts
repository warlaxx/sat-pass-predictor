import { describe, it, expect } from 'vitest';
import {
  aosUncertaintySeconds,
  compassPoint,
  elevationBand,
  formatAge,
  formatDuration,
} from './format';

describe('elevationBand', () => {
  it('puts each band on the right side of its threshold', () => {
    expect(elevationBand(10)).toBe('faint');
    expect(elevationBand(24.9)).toBe('faint');
    expect(elevationBand(25)).toBe('ordinary');
    expect(elevationBand(44.9)).toBe('ordinary');
    expect(elevationBand(45)).toBe('good');
    expect(elevationBand(70)).toBe('good');
    expect(elevationBand(70.1)).toBe('overhead');
  });
});

describe('compassPoint', () => {
  it('reads the four cardinals', () => {
    expect(compassPoint(0)).toBe('N');
    expect(compassPoint(90)).toBe('E');
    expect(compassPoint(180)).toBe('S');
    expect(compassPoint(270)).toBe('W');
  });

  it('rounds to the nearest of the sixteen sectors', () => {
    expect(compassPoint(292.5)).toBe('WNW');
    expect(compassPoint(112.4)).toBe('ESE');
  });

  /** 350 degrees is north, not north-north-west of nothing: the wrap has to close. */
  it('closes the circle', () => {
    expect(compassPoint(350)).toBe('N');
    expect(compassPoint(360)).toBe('N');
    expect(compassPoint(-10)).toBe('N');
  });
});

describe('formatDuration', () => {
  it('pads the seconds so a column of durations stays aligned', () => {
    expect(formatDuration(404)).toBe('6 min 44 s');
    expect(formatDuration(365)).toBe('6 min 05 s');
    expect(formatDuration(60)).toBe('1 min 00 s');
  });
});

describe('formatAge', () => {
  it('switches to days past twenty-four hours', () => {
    expect(formatAge(3600)).toBe('1 h');
    expect(formatAge(86_399)).toBe('23 h');
    expect(formatAge(86_400)).toBe('1 d 0 h');
    expect(formatAge(212_400)).toBe('2 d 11 h');
  });
});

describe('aosUncertaintySeconds', () => {
  /**
   * Not a precise figure - an order of magnitude. What the test pins is that the
   * uncertainty grows with the age and stays in seconds, which is the only claim the
   * banner makes.
   */
  it('grows with the age of the elements', () => {
    expect(aosUncertaintySeconds(0)).toBe(0);
    expect(aosUncertaintySeconds(86_400)).toBeLessThan(aosUncertaintySeconds(5 * 86_400));
    expect(aosUncertaintySeconds(7 * 86_400)).toBeLessThan(120);
  });
});
