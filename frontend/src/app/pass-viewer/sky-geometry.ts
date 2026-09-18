import { PhaseDto } from '../api/passes.model';

/** Projection only: orbital propagation belongs to the backend. East is on the right. */
export function project(point: Pick<PhaseDto, 'azimuthDeg' | 'elevationDeg'>): { x: number; y: number } {
  const radius = (90 - point.elevationDeg) / 90 * 144;
  const angle = point.azimuthDeg * Math.PI / 180;
  return { x: 180 + radius * Math.sin(angle), y: 180 - radius * Math.cos(angle) };
}

/** Interpolate the display between API samples, including across north (359° → 1°). */
export function sampleAt(track: readonly PhaseDto[], instant: number): PhaseDto | undefined {
  if (!track.length) return undefined;
  if (instant <= Date.parse(track[0].instant)) return track[0];
  const upper = track.findIndex(point => Date.parse(point.instant) >= instant);
  if (upper < 0) return track[track.length - 1];
  const a = track[upper - 1], b = track[upper];
  const fraction = (instant - Date.parse(a.instant)) / (Date.parse(b.instant) - Date.parse(a.instant));
  const delta = ((b.azimuthDeg - a.azimuthDeg + 540) % 360) - 180;
  return {
    instant: new Date(instant).toISOString(),
    azimuthDeg: (a.azimuthDeg + delta * fraction + 360) % 360,
    elevationDeg: a.elevationDeg + (b.elevationDeg - a.elevationDeg) * fraction,
    rangeKm: a.rangeKm + (b.rangeKm - a.rangeKm) * fraction,
  };
}
