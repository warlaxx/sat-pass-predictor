import { describe, it, expect } from 'vitest';
import { groupIntoNights, nightKey } from './nights';
import { PassDto } from '../api/passes.model';

/** Only the fields the grouping reads; the rest would be noise in this file. */
function passAt(localIso: string): PassDto {
  const instant = new Date(localIso).toISOString();
  const phase = { instant, azimuthDeg: 180, elevationDeg: 10, rangeKm: 1500 };
  return {
    aos: phase,
    culmination: { ...phase, elevationDeg: 40 },
    los: phase,
    durationSeconds: 300,
    track: [],
  };
}

describe('nightKey', () => {
  /**
   * A night is named after its evening. Without the twelve-hour shift, a pass at 02:00
   * would open a column of its own between two that belong to the same night out.
   */
  it('puts an after-midnight pass on the evening that precedes it', () => {
    expect(nightKey(new Date('2026-09-22T21:18:00').toISOString())).toBe('2026-09-22');
    expect(nightKey(new Date('2026-09-23T02:05:00').toISOString())).toBe('2026-09-22');
    expect(nightKey(new Date('2026-09-23T11:59:00').toISOString())).toBe('2026-09-22');
  });

  it('starts a new night at local noon', () => {
    expect(nightKey(new Date('2026-09-23T12:01:00').toISOString())).toBe('2026-09-23');
  });

  it('crosses a month boundary without a special case', () => {
    expect(nightKey(new Date('2026-10-01T03:00:00').toISOString())).toBe('2026-09-30');
  });
});

describe('groupIntoNights', () => {
  it('returns nothing for no passes, rather than an empty column', () => {
    expect(groupIntoNights([])).toEqual([]);
  });

  it('gathers the passes of one night into one column', () => {
    const nights = groupIntoNights([
      passAt('2026-09-22T21:18:00'),
      passAt('2026-09-23T01:02:00'),
    ]);

    expect(nights).toHaveLength(1);
    expect(nights[0].key).toBe('2026-09-22');
    expect(nights[0].passes).toHaveLength(2);
  });

  /**
   * The point of the ribbon: a night with nothing is a result, not a gap to close up.
   * Dropping it would make two nights a week apart look adjacent.
   */
  it('keeps the empty nights in between', () => {
    const nights = groupIntoNights([
      passAt('2026-09-22T21:18:00'),
      passAt('2026-09-25T20:40:00'),
    ]);

    expect(nights.map((night) => night.key)).toEqual([
      '2026-09-22', '2026-09-23', '2026-09-24', '2026-09-25',
    ]);
    expect(nights[1].passes).toEqual([]);
    expect(nights[2].passes).toEqual([]);
  });

  it('labels every column', () => {
    const [night] = groupIntoNights([passAt('2026-09-22T21:18:00')]);

    expect(night.label).not.toHaveLength(0);
    expect(night.longLabel.length).toBeGreaterThan(night.label.length);
  });
});
