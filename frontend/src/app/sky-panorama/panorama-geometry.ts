import { PhaseDto } from '../api/passes.model';
import { D2R, LatLon, R2D, subsolarPoint } from '../globe/globe-geometry';

/**
 * The horizon panorama: azimuth across, elevation up, as someone standing outside and
 * turning their head would see the pass.
 *
 * Projection only, like the sky chart: every angle comes from the API. The one thing
 * computed here is the Sun's direction, and only to place the twilight glow - never to
 * decide whether the satellite is visible.
 */

/**
 * Azimuths made continuous along the track: 350° followed by 10° becomes 350, 370.
 * A pass that crosses north would otherwise be drawn as a line across the whole sky.
 */
export function unwrapAzimuths(track: readonly Pick<PhaseDto, 'azimuthDeg'>[]): number[] {
  const out: number[] = [];
  for (const point of track) {
    if (!out.length) { out.push(point.azimuthDeg); continue; }
    const previous = out[out.length - 1];
    const delta = ((point.azimuthDeg - previous + 540) % 360) - 180;
    out.push(previous + delta);
  }
  return out;
}

/** The azimuth the panorama faces: the middle of the pass's sweep, in [0, 360). */
export function facingAzimuth(track: readonly Pick<PhaseDto, 'azimuthDeg'>[]): number {
  const azimuths = unwrapAzimuths(track);
  if (!azimuths.length) return 0;
  const middle = (Math.min(...azimuths) + Math.max(...azimuths)) / 2;
  return ((middle % 360) + 360) % 360;
}

export interface PanoramaFrame {
  readonly width: number;
  readonly height: number;
  /** y of the 0° line. */
  readonly horizonY: number;
  /** Pixels per degree of elevation. */
  readonly elevationScale: number;
  /** Pixels per degree of azimuth. */
  readonly azimuthScale: number;
  /** Unwrapped azimuth drawn at the horizontal centre. */
  readonly centreAz: number;
}

export const GROUND_BAND = 46;

/**
 * Fits the pass in the frame.
 *
 * Elevation is scaled to the pass's own peak, with a floor at 40°: a 12° pass drawn at
 * full height would look like a zenith pass, and the gridlines labelled 10°, 30°, 60°
 * are what keep the drawing honest. Azimuth gets the same scale as elevation when it
 * fits - degrees look like degrees - and is squeezed only when the sweep is wider than
 * the screen.
 */
export function panoramaFrame(track: readonly Pick<PhaseDto, 'azimuthDeg' | 'elevationDeg'>[], width: number, height: number): PanoramaFrame {
  const azimuths = unwrapAzimuths(track);
  const low = Math.min(...azimuths);
  const high = Math.max(...azimuths);
  const peak = Math.max(40, ...track.map(point => point.elevationDeg));
  const horizonY = height - GROUND_BAND;
  const elevationScale = (horizonY - 44) / peak;
  const azimuthScale = Math.min(elevationScale * 0.9, (width * 0.84) / Math.max(high - low, 1));
  return { width, height, horizonY, elevationScale, azimuthScale, centreAz: (low + high) / 2 };
}

export function toScreen(frame: PanoramaFrame, unwrappedAz: number, elevationDeg: number): { x: number; y: number } {
  return {
    x: frame.width / 2 + (unwrappedAz - frame.centreAz) * frame.azimuthScale,
    y: frame.horizonY - elevationDeg * frame.elevationScale,
  };
}

/** Brings any azimuth to the unwrapped turn closest to the centre of the frame. */
export function nearestTurn(frame: PanoramaFrame, azimuthDeg: number): number {
  return azimuthDeg + 360 * Math.round((frame.centreAz - azimuthDeg) / 360);
}

/** Azimuth and elevation of the Sun seen from the observer, low precision. */
export function sunPosition(observer: LatLon, instantMs: number): { azimuthDeg: number; elevationDeg: number } {
  const sun = subsolarPoint(instantMs);
  const lat1 = observer.latitudeDeg * D2R;
  const lat2 = sun.latitudeDeg * D2R;
  const dLon = (sun.longitudeDeg - observer.longitudeDeg) * D2R;
  const cosDistance = Math.sin(lat1) * Math.sin(lat2) + Math.cos(lat1) * Math.cos(lat2) * Math.cos(dLon);
  const elevationDeg = 90 - Math.acos(Math.max(-1, Math.min(1, cosDistance))) * R2D;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  const azimuthDeg = ((Math.atan2(y, x) * R2D) + 360) % 360;
  return { azimuthDeg, elevationDeg };
}

export interface Star {
  readonly x: number;
  readonly y: number;
  readonly r: number;
  readonly opacity: number;
}

/**
 * A decorative star field, the same one every time for a given size.
 *
 * Seeded rather than random so the sky does not reshuffle on every change detection or
 * every selection: it is scenery, and scenery that moves draws the eye away from the one
 * thing that should.
 */
export function starField(width: number, horizonY: number): Star[] {
  let seed = 0x2f6b1d;
  const next = (): number => {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    return seed / 0x100000000;
  };
  const count = Math.round((width * horizonY) / 2600);
  const stars: Star[] = [];
  for (let i = 0; i < count; i++) {
    const y = next() * (horizonY - 8);
    stars.push({
      x: next() * width,
      y,
      r: 0.35 + next() * next() * 1.1,
      // Fainter towards the horizon, where the air is thickest.
      opacity: (0.25 + next() * 0.6) * (1 - 0.6 * (y / horizonY)),
    });
  }
  return stars;
}

/** A low, uneven skyline so the horizon reads as ground, not as a ruler. */
export function skyline(width: number, horizonY: number): string {
  const points: string[] = [`0,${horizonY + GROUND_BAND}`];
  for (let x = 0; x <= width + 10; x += 10) {
    const y = horizonY - 1.5
      + 2.2 * Math.sin(x / 57) + 1.4 * Math.sin(x / 23 + 1.3) + 1.8 * Math.sin(x / 131 + 0.4);
    points.push(`${x},${y.toFixed(1)}`);
  }
  points.push(`${width + 10},${horizonY + GROUND_BAND}`);
  return points.join(' ');
}
