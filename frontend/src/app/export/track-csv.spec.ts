import { describe, it, expect } from 'vitest';
import { PassDto, PassesResponse, TrackPointDto } from '../api/passes.model';
import { buildTrackCsv, trackFileName } from './track-csv';

const point = (seconds: number, dopplerHz: number | null): TrackPointDto => ({
  instant: new Date(Date.UTC(2026, 8, 22, 19, 18, 54 + seconds)).toISOString(),
  azimuthDeg: 292.5, elevationDeg: 10, rangeKm: 1553.2, rangeRateKmS: -6.21, dopplerHz,
  subPoint: { latitudeDeg: 38.71, longitudeDeg: -4.92, altitudeKm: 419.6 },
  illuminated: true, visible: false,
});

const passWith = (track: TrackPointDto[]): PassDto =>
  ({ aos: track[0], culmination: track[0], los: track[track.length - 1], durationSeconds: 10, track });

describe('buildTrackCsv', () => {
  it('writes one row per API sample under a stable header, in CRLF lines', () => {
    const csv = buildTrackCsv(passWith([point(0, 3021.456), point(10, -12.34)]));
    const lines = csv.split('\r\n');
    expect(lines[0]).toBe('utc,azimuth_deg,elevation_deg,range_km,range_rate_km_s,doppler_hz,'
      + 'sub_latitude_deg,sub_longitude_deg,altitude_km,sunlit,potentially_visible');
    expect(lines[1]).toBe('2026-09-22T19:18:54.000Z,292.500,10.000,1553.200,-6.2100,3021.5,38.7100,-4.9200,419.600,true,false');
    expect(lines[2].split(',')[5]).toBe('-12.3');
    expect(lines).toHaveLength(4);
    expect(lines[3]).toBe('');
  });

  it('leaves the Doppler column empty when no frequency was requested', () => {
    const row = buildTrackCsv(passWith([point(0, null)])).split('\r\n')[1];
    expect(row.split(',')[5]).toBe('');
    expect(row.split(',')).toHaveLength(11);
  });
});

describe('trackFileName', () => {
  it('names the satellite and the rise of the pass', () => {
    const response = { satellite: { noradId: 25544, name: 'ISS (ZARYA)' } } as PassesResponse;
    expect(trackFileName(response, passWith([point(0, null)]))).toBe('iss-zarya-20260922T191854Z-track.csv');
  });
});
