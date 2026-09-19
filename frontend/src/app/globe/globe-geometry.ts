import { TrackPointDto } from '../api/passes.model';

/** Mean radius, matching the sphere the backend projects `subPoint` onto. */
export const EARTH_RADIUS_KM = 6371;

export const D2R = Math.PI / 180;
export const R2D = 180 / Math.PI;

export interface LatLon {
  readonly latitudeDeg: number;
  readonly longitudeDeg: number;
}

/** A point on the unit sphere, y up, matching three.js's convention. */
export interface UnitVector {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

export function toUnitVector(point: LatLon, radius = 1): UnitVector {
  const lat = point.latitudeDeg * D2R;
  const lon = point.longitudeDeg * D2R;
  return {
    x: radius * Math.cos(lat) * Math.sin(lon),
    y: radius * Math.sin(lat),
    z: radius * Math.cos(lat) * Math.cos(lon),
  };
}

/**
 * The subsolar point (where the Sun is at the zenith) at a given instant.
 *
 * Low-precision solar position (accurate to a few hundredths of a degree — see the
 * Astronomical Almanac's approximate formula). It positions a `DirectionalLight` so the
 * day/night terminator falls where it really does; it is not used to decide whether the
 * satellite itself is sunlit, which the API calculates separately.
 */
export function subsolarPoint(instantMs: number): LatLon {
  const julianDate = instantMs / 86400000 + 2440587.5;
  const daysSinceJ2000 = julianDate - 2451545.0;
  const meanLongitude = (280.46 + 0.9856474 * daysSinceJ2000) * D2R;
  const meanAnomaly = (357.528 + 0.9856003 * daysSinceJ2000) * D2R;
  const eclipticLongitude = meanLongitude
    + 1.915 * D2R * Math.sin(meanAnomaly)
    + 0.02 * D2R * Math.sin(2 * meanAnomaly);
  const obliquity = 23.439 * D2R;
  const rightAscension = Math.atan2(Math.cos(obliquity) * Math.sin(eclipticLongitude), Math.cos(eclipticLongitude));
  const declination = Math.asin(Math.sin(obliquity) * Math.sin(eclipticLongitude));
  let greenwichSiderealTime = (280.46061837 + 360.98564736629 * daysSinceJ2000) % 360;
  if (greenwichSiderealTime < 0) greenwichSiderealTime += 360;
  return {
    latitudeDeg: declination * R2D,
    longitudeDeg: (((rightAscension * R2D - greenwichSiderealTime) % 360) + 540) % 360 - 180,
  };
}

/** True where the Sun is above the horizon — the ground's daylight side, not the satellite's. */
export function isDaylit(point: LatLon, sun: LatLon): boolean {
  const p = toUnitVector(point), s = toUnitVector(sun);
  return p.x * s.x + p.y * s.y + p.z * s.z > 0;
}

/** Destination point given a start, an initial bearing and an angular distance, all in degrees. */
export function destinationPoint(start: LatLon, bearingDeg: number, angularDistanceDeg: number): LatLon {
  const lat = start.latitudeDeg * D2R, lon = start.longitudeDeg * D2R;
  const bearing = bearingDeg * D2R, distance = angularDistanceDeg * D2R;
  const destLat = Math.asin(Math.sin(lat) * Math.cos(distance) + Math.cos(lat) * Math.sin(distance) * Math.cos(bearing));
  const destLon = lon + Math.atan2(
    Math.sin(bearing) * Math.sin(distance) * Math.cos(lat),
    Math.cos(distance) - Math.sin(lat) * Math.sin(destLat),
  );
  return { latitudeDeg: destLat * R2D, longitudeDeg: ((destLon * R2D + 540) % 360) - 180 };
}

/**
 * Angular radius of the visibility circle around a sub-satellite point: the locus of
 * ground points from which the satellite is exactly at `minElevationDeg`.
 * `acos(Re / (Re + h) * cos(threshold)) - threshold`.
 */
export function visibilityRadiusDeg(altitudeKm: number, minElevationDeg: number): number {
  const threshold = minElevationDeg * D2R;
  const ratio = EARTH_RADIUS_KM / (EARTH_RADIUS_KM + altitudeKm) * Math.cos(threshold);
  return Math.acos(Math.min(1, Math.max(-1, ratio))) * R2D - minElevationDeg;
}

export interface SubPointSample {
  readonly latitudeDeg: number;
  readonly longitudeDeg: number;
  readonly altitudeKm: number;
}

/** Interpolates `subPoint` between API samples, including across the antimeridian. */
export function sampleSubPointAt(track: readonly TrackPointDto[], instantMs: number): SubPointSample | undefined {
  if (!track.length) return undefined;
  const first = Date.parse(track[0].instant);
  if (instantMs <= first) return track[0].subPoint;
  const upper = track.findIndex(point => Date.parse(point.instant) >= instantMs);
  if (upper < 0) return track[track.length - 1].subPoint;
  const a = track[upper - 1].subPoint, b = track[upper].subPoint;
  const ta = Date.parse(track[upper - 1].instant), tb = Date.parse(track[upper].instant);
  const fraction = (instantMs - ta) / (tb - ta);
  const deltaLon = ((b.longitudeDeg - a.longitudeDeg + 540) % 360) - 180;
  return {
    latitudeDeg: a.latitudeDeg + (b.latitudeDeg - a.latitudeDeg) * fraction,
    longitudeDeg: ((a.longitudeDeg + deltaLon * fraction + 540) % 360) - 180,
    altitudeKm: a.altitudeKm + (b.altitudeKm - a.altitudeKm) * fraction,
  };
}
