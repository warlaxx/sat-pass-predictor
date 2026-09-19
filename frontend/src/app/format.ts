/**
 * Pure presentation helpers.
 *
 * They live outside the components because they are the only part of the frontend with
 * anything to get wrong, and a pure function is the cheapest thing in this codebase to
 * test. Nothing here computes an orbit: it formats what the API already decided.
 */

/**
 * Where a pass sits on the scale the whole interface uses: ribbon, table, tiles.
 *
 * Four bands, not a gradient. A continuous scale would make 46 degrees and 44 degrees
 * look different for no reason; these thresholds say something a reader can act on -
 * below 25 degrees a pass grazes the horizon and the roofs win, above 70 it goes
 * practically overhead.
 */
export type ElevationBand = 'faint' | 'ordinary' | 'good' | 'overhead';

export function elevationBand(maxElevationDeg: number): ElevationBand {
  if (maxElevationDeg > 70) return 'overhead';
  if (maxElevationDeg >= 45) return 'good';
  if (maxElevationDeg >= 25) return 'ordinary';
  return 'faint';
}

const BAND_VARIABLE: Record<ElevationBand, string> = {
  faint: 'var(--accent-dim)',
  ordinary: 'var(--accent)',
  good: 'var(--lit)',
  overhead: 'var(--hot)',
};

export function elevationColour(maxElevationDeg: number): string {
  return BAND_VARIABLE[elevationBand(maxElevationDeg)];
}

const COMPASS = [
  'N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
  'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW',
];

/**
 * Azimuth as a compass point, on sixteen sectors of 22.5 degrees.
 *
 * "WNW" is what someone standing in a garden can use; "292.5 degrees" is what a
 * telescope mount can use. The table shows both, because the project is aimed at people
 * who read the second and go outside with the first.
 */
export function compassPoint(azimuthDeg: number): string {
  const normalised = ((azimuthDeg % 360) + 360) % 360;
  return COMPASS[Math.round(normalised / 22.5) % 16];
}

/** "6 min 44 s" - a pass lasts minutes, so hours never appear and are not handled. */
export function formatDuration(seconds: number): string {
  const whole = Math.round(seconds);
  const minutes = Math.floor(whole / 60);
  return `${minutes} min ${String(whole % 60).padStart(2, '0')} s`;
}

/** "2 d 10 h" for a TLE age. Days first: it is the unit the drift is expressed in. */
export function formatAge(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  return hours < 24 ? `${hours} h` : `${Math.floor(hours / 24)} d ${hours % 24} h`;
}

/**
 * Order of magnitude of the position error of the prediction, in kilometres.
 *
 * SGP4 drifts by roughly 1 to 3 km per day in low Earth orbit, more during a geomagnetic
 * storm. This is a range, deliberately: a single figure would be a precision claim, and
 * the true value depends on solar activity nobody is reading here. It is displayed as
 * "1 to 6 km", never as "3.2 km".
 */
export function expectedDriftKm(ageSeconds: number): { readonly low: number; readonly high: number } {
  const days = ageSeconds / 86_400;
  return { low: Math.round(days * 1), high: Math.round(days * 3) };
}

/**
 * The same error, read as an uncertainty on the AOS time, in seconds.
 *
 * The along-track component dominates: an object late along its own track shows up late,
 * and in low Earth orbit the ground speed is about 7.7 km/s. Dividing the drift by that
 * speed turns kilometres nobody can picture into seconds anyone can - "this pass may
 * start half a minute either side" is the sentence the banner exists to make possible.
 */
const ORBITAL_SPEED_KM_PER_S = 7.66;

export function aosUncertaintySeconds(ageSeconds: number): number {
  return Math.round(expectedDriftKm(ageSeconds).high / ORBITAL_SPEED_KM_PER_S);
}

/**
 * A pass worth going outside for: high enough to clear roofs and trees with room to
 * spare. The table flags it and the selected-pass tile says so in words.
 */
export function isRemarkable(maxElevationDeg: number): boolean {
  return maxElevationDeg >= 50;
}

const BAND_WORDS: Record<ElevationBand, string> = {
  faint: 'grazes the horizon',
  ordinary: 'clears the rooftops',
  good: 'high in the sky',
  overhead: 'almost overhead',
};

/** The elevation band, said in the words someone standing outside would use. */
export function describeElevation(maxElevationDeg: number): string {
  return BAND_WORDS[elevationBand(maxElevationDeg)];
}

/**
 * "UTC+2", "UTC−3:30", "UTC" - the browser's offset at a given instant.
 *
 * Per instant, not once per page: a window of ten nights can straddle a daylight-saving
 * change, and the label has to say which side of it each time sits on.
 */
export function utcOffsetLabel(instant: string | number): string {
  const minutes = -new Date(instant).getTimezoneOffset();
  if (minutes === 0) return 'UTC';
  const sign = minutes > 0 ? '+' : '−';
  const hours = Math.floor(Math.abs(minutes) / 60);
  const rest = Math.abs(minutes) % 60;
  return `UTC${sign}${hours}${rest ? ':' + String(rest).padStart(2, '0') : ''}`;
}

/**
 * The instant the satellite enters (or leaves) the Earth's shadow during a pass, if the
 * API says it does.
 *
 * Reads `illuminated` and nothing else. Until milestone 10 that field is `false` on every
 * point, so this returns `undefined` and every caller draws the neutral treatment.
 */
export function shadowEntry(track: readonly { instant: string; illuminated: boolean }[]): string | undefined {
  for (let i = 1; i < track.length; i++) {
    if (track[i - 1].illuminated && !track[i].illuminated) return track[i].instant;
  }
  return undefined;
}

/** True once the API reports illumination at all, i.e. from milestone 10 on. */
export function hasIllumination(track: readonly { illuminated: boolean }[]): boolean {
  return track.some(point => point.illuminated);
}
