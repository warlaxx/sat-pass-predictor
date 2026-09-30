/** A position the pass endpoint accepts, rounded as it will be displayed. */
export interface Position {
  readonly lat: number;
  readonly lon: number;
  /** Metres above the WGS84 ellipsoid, or null when the device does not measure one. */
  readonly alt: number | null;
}

const GEOLOCATION_ERRORS: Record<number, string> = {
  1: 'Permission refused. Type the position in instead.',
  2: 'Your device could not determine a position.',
  3: 'The position request timed out.',
};

function round(value: number, decimals: number): number {
  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
}

/**
 * The browser's position, or a sentence saying why there is none.
 *
 * Geolocation is a permission the user can refuse, a sensor that can fail and a call that
 * can hang. Each of those rejects with a message meant for the screen rather than a code,
 * so every page that offers "use my position" says the same thing when it fails.
 */
export function requestPosition(): Promise<Position> {
  if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
    return Promise.reject(new Error('This browser does not offer geolocation. Type the position in.'));
  }
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({
        // Four decimals is about eleven metres. An observer a hundred metres off changes
        // nothing in a pass four hundred kilometres up, and the extra digits would only
        // put a more precise home address on screen.
        lat: round(position.coords.latitude, 4),
        lon: round(position.coords.longitude, 4),
        // The Geolocation API returns altitude above the WGS84 ellipsoid, which is exactly
        // the datum ObserverLocation expects - no conversion, and no guess when absent.
        alt: position.coords.altitude === null ? null : Math.round(position.coords.altitude),
      }),
      (error) => reject(new Error(GEOLOCATION_ERRORS[error.code] ?? 'Position unavailable.')),
      {
        // Metres are pointless here and a GPS fix costs battery and seconds: the coarse
        // network position is already far below the accuracy this computation needs.
        enableHighAccuracy: false,
        timeout: 10_000,
        maximumAge: 300_000,
      },
    );
  });
}
