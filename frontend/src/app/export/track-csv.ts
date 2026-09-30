import { PassDto, PassesResponse } from '../api/passes.model';
import { saveText, slug } from '../shared/download';

/**
 * The sampled track of one pass, as CSV: what a rotator script, a spreadsheet or a
 * Gpredict-style tool can read without knowing this API.
 *
 * One row per API sample - every 10 s, plus the rise, the culmination and the set -
 * and nothing interpolated: a file handed to an antenna should hold the points the
 * backend computed, not the ones the browser drew between them.
 *
 * Instants in ISO-8601 UTC, numbers with a dot and no thousands separator, whatever the
 * browser's locale: a CSV read differently in Lyon and in Boston is not a data file.
 * The Doppler column is empty when no frequency was requested rather than 0, which
 * would read as "no shift".
 */

const HEADER = [
  'utc',
  'azimuth_deg',
  'elevation_deg',
  'range_km',
  'range_rate_km_s',
  'doppler_hz',
  'sub_latitude_deg',
  'sub_longitude_deg',
  'altitude_km',
  'sunlit',
  'potentially_visible',
];

export function buildTrackCsv(pass: PassDto): string {
  const rows = pass.track.map(point => [
    point.instant,
    point.azimuthDeg.toFixed(3),
    point.elevationDeg.toFixed(3),
    point.rangeKm.toFixed(3),
    point.rangeRateKmS.toFixed(4),
    point.dopplerHz === null ? '' : point.dopplerHz.toFixed(1),
    point.subPoint.latitudeDeg.toFixed(4),
    point.subPoint.longitudeDeg.toFixed(4),
    point.subPoint.altitudeKm.toFixed(3),
    String(point.illuminated),
    String(point.visible),
  ]);
  // RFC 4180 lines end in CRLF, including the last one.
  return [HEADER, ...rows].map(row => row.join(',')).join('\r\n') + '\r\n';
}

/** "iss-zarya-20260922T191854Z-track.csv": the satellite and the pass, sortable by name. */
export function trackFileName(response: PassesResponse, pass: PassDto): string {
  const stamp = new Date(pass.aos.instant).toISOString().replace(/\.\d{3}/, '').replace(/[-:]/g, '');
  return `${slug(response.satellite.name) || response.satellite.noradId}-${stamp}-track.csv`;
}

/** Downloads the track of a pass; false when it has none to save. */
export function saveTrackCsv(response: PassesResponse, pass: PassDto): boolean {
  if (pass.track.length === 0) return false;
  saveText(buildTrackCsv(pass), 'text/csv;charset=utf-8', trackFileName(response, pass));
  return true;
}
