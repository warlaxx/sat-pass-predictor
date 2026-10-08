/** The sign-up the backend's `POST /api/alerts` takes (ABD-42). */
export interface AlertSignup {
  readonly email: string;
  readonly noradId: number;
  readonly lat: number;
  readonly lon: number;
  readonly minElevationDeg: number;
  readonly maxCloudPercent: number;
  readonly maxMagnitude: number | null;
  readonly timeZone: string;
  readonly locale: 'en' | 'fr';
}

/** What `POST /api/alerts/confirm` gives back: what the reader just turned on, never the address. */
export interface ConfirmedAlert {
  readonly noradId: number;
  readonly lat: number;
  readonly lon: number;
  readonly minElevationDeg: number;
  readonly maxCloudPercent: number;
}
