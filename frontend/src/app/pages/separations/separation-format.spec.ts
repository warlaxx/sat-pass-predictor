import { describe, expect, it } from 'vitest';
import { RecordedDate } from '../../api/separations.model';
import { isGeostationary, orbitLabel, parentCell, precisionLabel, recordedDateLabel, recordedDateShort, timeSinceLaunch } from './separation-format';

const date = (text: string, at: string, precision: RecordedDate['precision'], uncertain = false): RecordedDate =>
  ({ text, at, precision, uncertain });

describe('recordedDateLabel', () => {
  it('never shows more than the record knows', () => {
    expect(recordedDateLabel(date('2026 Sep?', '2026-09-01T00:00:00Z', 'MONTH', true), 'en-GB')).toBe('September 2026');
    expect(recordedDateLabel(date('2026 Jul 20?', '2026-07-20T00:00:00Z', 'DAY', true), 'en-GB')).toBe('20 July 2026');
    expect(recordedDateLabel(date('2026 Q3?', '2026-07-01T00:00:00Z', 'QUARTER', true), 'en-GB')).toBe('Q3 2026');
    expect(recordedDateLabel(date('1960s?', '1960-01-01T00:00:00Z', 'DECADE', true), 'en-GB')).toBe('1960s');
    expect(recordedDateLabel(date('1995', '1995-01-01T00:00:00Z', 'YEAR'), 'en-GB')).toBe('1995');
  });

  it('gives times in UTC, and says so', () => {
    expect(recordedDateLabel(date('2026 May 29 1120', '2026-05-29T11:20:00Z', 'MINUTE'), 'en-GB')).toBe('29 May 2026 at 11:20 UTC');
    expect(recordedDateLabel(date('2026 May 29 1120', '2026-05-29T11:20:00Z', 'MINUTE'), 'fr-FR')).toBe('29 mai 2026 à 11:20 UTC');
  });

  it('falls back to the record when the date could not be read', () => {
    expect(recordedDateLabel({ text: 'soon', at: null, precision: null, uncertain: false }, 'en-GB')).toBe('soon');
  });
});

describe('recordedDateShort', () => {
  it('abbreviates the month and keeps the precision', () => {
    expect(recordedDateShort(date('2026 Sep?', '2026-09-01T00:00:00Z', 'MONTH', true), 'en-GB')).toBe('Sept 2026');
    expect(recordedDateShort(date('2026 Jul 29 1950?', '2026-07-29T19:50:00Z', 'MINUTE', true), 'en-GB')).toBe('29 Jul 2026, 19:50');
  });
});

describe('precisionLabel', () => {
  it('says how much is known', () => {
    expect(precisionLabel(date('2026 Sep?', '2026-09-01T00:00:00Z', 'MONTH', true))).toBe('to the month · uncertain');
    expect(precisionLabel(date('2026 Jun 21', '2026-06-21T00:00:00Z', 'DAY'))).toBe('to the day');
  });
});

describe('timeSinceLaunch', () => {
  const launch = date('2024 Jul 30', '2024-07-30T00:00:00Z', 'DAY');

  it('counts months between dates known at least to the month', () => {
    expect(timeSinceLaunch(launch, date('2026 Sep?', '2026-09-01T00:00:00Z', 'MONTH', true))).toBe('≈ 26 months');
  });

  it('counts days for a separation in the first weeks', () => {
    expect(timeSinceLaunch(launch, date('2024 Aug 10', '2024-08-10T00:00:00Z', 'DAY'))).toBe('11 days');
  });

  it('invents nothing from a vague date', () => {
    expect(timeSinceLaunch(launch, date('2026?', '2026-01-01T00:00:00Z', 'YEAR', true))).toBeUndefined();
    expect(timeSinceLaunch(null, date('2026 Sep?', '2026-09-01T00:00:00Z', 'MONTH', true))).toBeUndefined();
  });
});

describe('orbitLabel', () => {
  it('writes perigee and apogee with thin grouping', () => {
    expect(orbitLabel({ perigeeKm: 593, apogeeKm: 1085, inclinationDeg: 97, orbitClass: 'LEO/R' })).toBe('593 × 1 085 km');
  });

  it('says when an object left Earth orbit', () => {
    expect(orbitLabel({ perigeeKm: 180, apogeeKm: null, inclinationDeg: 30, orbitClass: 'HCO' })).toBe('Escaped Earth orbit');
  });

  it('knows a geostationary orbit by its class', () => {
    expect(isGeostationary({ perigeeKm: 35800, apogeeKm: 36100, inclinationDeg: 0, orbitClass: 'GEO/D' })).toBe(true);
    expect(isGeostationary({ perigeeKm: 382, apogeeKm: 394, inclinationDeg: 41, orbitClass: 'LLEO/I' })).toBe(false);
  });
});

describe('parentCell', () => {
  it('reads the asterisk as a warning about the launch designation, not about the parent', () => {
    expect(parentCell('S03504*')).toEqual({ id: 'S03504', designationMayDiffer: true, port: null });
  });

  it('reads what follows the spaces as a port location, kept as recorded', () => {
    expect(parentCell('S16273  AL')).toEqual({ id: 'S16273', designationMayDiffer: false, port: 'AL' });
    expect(parentCell('A07559 EFU5')).toEqual({ id: 'A07559', designationMayDiffer: false, port: 'EFU5' });
    expect(parentCell('A09547* N')).toEqual({ id: 'A09547', designationMayDiffer: true, port: 'N' });
  });

  it('leaves a plain identifier plain, and refuses what is not one', () => {
    expect(parentCell('S66645')).toEqual({ id: 'S66645', designationMayDiffer: false, port: null });
    expect(parentCell(null)).toBeUndefined();
    expect(parentCell('Earth')).toBeUndefined();
  });
});
