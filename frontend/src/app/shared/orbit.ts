/** Earth's gravitational parameter (WGS84), km³/s². */
const MU_KM3_S2 = 398_600.4418;
/** WGS84 equatorial radius, km. */
const EARTH_RADIUS_KM = 6378.137;

/** The orbit a TLE describes, in the words a reader expects rather than SGP4's. */
export interface OrbitSummary {
  readonly inclinationDeg: number;
  readonly eccentricity: number;
  readonly revolutionsPerDay: number;
  readonly periodMinutes: number;
  readonly perigeeKm: number;
  readonly apogeeKm: number;
  readonly revolutionNumber: number;
}

/**
 * Reads the shape of the orbit off the second line of a TLE.
 *
 * The altitudes come from Kepler's third law applied to the mean motion, which is not
 * how SGP4 uses it: SGP4's mean elements are its own, and the true altitude varies along
 * the orbit with Earth's oblateness. The result is right to a few kilometres - enough to
 * say "about 420 km", which is all a summary claims. Positions still come only from the
 * backend's propagation, never from here.
 *
 * Undefined when the line is not a well-formed line 2: a summary made of NaN is worse
 * than no summary.
 */
export function orbitFromTle(line2: string): OrbitSummary | undefined {
  if (line2.length < 68 || line2[0] !== '2') return undefined;
  const inclinationDeg = Number(line2.substring(8, 16));
  const eccentricity = Number(`0.${line2.substring(26, 33).trim()}`);
  const revolutionsPerDay = Number(line2.substring(52, 63));
  const revolutionNumber = Number(line2.substring(63, 68));
  if (![inclinationDeg, eccentricity, revolutionsPerDay].every(Number.isFinite) || revolutionsPerDay <= 0) {
    return undefined;
  }
  const meanMotionRadS = (revolutionsPerDay * 2 * Math.PI) / 86_400;
  const semiMajorAxisKm = Math.cbrt(MU_KM3_S2 / meanMotionRadS ** 2);
  return {
    inclinationDeg,
    eccentricity,
    revolutionsPerDay,
    periodMinutes: 1440 / revolutionsPerDay,
    perigeeKm: semiMajorAxisKm * (1 - eccentricity) - EARTH_RADIUS_KM,
    apogeeKm: semiMajorAxisKm * (1 + eccentricity) - EARTH_RADIUS_KM,
    revolutionNumber: Number.isFinite(revolutionNumber) ? revolutionNumber : 0,
  };
}

/** The usual name of the regime, from the mean altitude. */
export function orbitRegime(orbit: OrbitSummary): string {
  const mean = (orbit.perigeeKm + orbit.apogeeKm) / 2;
  if (orbit.eccentricity > 0.25) return $localize`Highly elliptical orbit`;
  if (mean < 2000) return $localize`Low Earth orbit`;
  if (Math.abs(mean - 35_786) < 500) return $localize`Geosynchronous orbit`;
  return $localize`Medium Earth orbit`;
}
