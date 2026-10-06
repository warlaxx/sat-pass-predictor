import { describe, it, expect } from 'vitest';
import { PassDto, TrackPointDto } from '../api/passes.model';
import { formatMagnitude, passBrightness } from './brightness';

function point(visible: boolean, magnitude?: number): TrackPointDto {
  return {
    instant: '2026-10-06T19:00:00Z', azimuthDeg: 0, elevationDeg: 30, rangeKm: 600, rangeRateKmS: 0, dopplerHz: null,
    subPoint: { latitudeDeg: 0, longitudeDeg: 0, altitudeKm: 420 }, illuminated: magnitude !== undefined || visible,
    visible, ...(magnitude === undefined ? {} : { magnitude }),
  };
}

const pass = (track: TrackPointDto[]) => ({ track } as unknown as PassDto);

describe('passBrightness', () => {
  it('keeps the brightest visible sample and names what it takes to see it', () => {
    expect(passBrightness(pass([point(true, 1.2), point(true, -2.9), point(false, -3.5)])))
      .toEqual({ magnitude: -2.9, verdict: 'naked-eye' });
    expect(passBrightness(pass([point(true, 5.1)]))?.verdict).toBe('binoculars');
    expect(passBrightness(pass([point(true, 8)]))?.verdict).toBe('too-faint');
  });

  it('says nothing without a magnitude, or in daylight', () => {
    expect(passBrightness(pass([point(true)]))).toBeUndefined();
    expect(passBrightness(pass([point(false, -3)]))).toBeUndefined();
  });

  it('writes magnitudes with a real minus sign and one decimal', () => {
    expect(formatMagnitude(-2.94)).toBe('−2.9');
    expect(formatMagnitude(1.25)).toBe('1.3');
    expect(formatMagnitude(-0.01)).toBe('0.0');
  });
});
