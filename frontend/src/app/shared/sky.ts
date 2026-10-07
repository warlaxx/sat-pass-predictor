import { CloudForecastDto } from '../api/weather.model';

/**
 * The sky at a pass's peak (ABD-36), from MET Norway's cloud cover. In oktas, the
 * observers' own unit: up to two eighths is clear, six or more is overcast.
 */
export interface Sky {
  readonly cloudPercent: number;
  readonly verdict: 'clear' | 'partly' | 'overcast';
}

export const CLEAR_UP_TO = 25;
export const OVERCAST_FROM = 75;

/**
 * The forecast step that holds at `instant`, or undefined past the forecast: a pass next
 * week has no sky rather than a guess.
 */
export function skyAt(forecast: CloudForecastDto | undefined, instant: string): Sky | undefined {
  if (!forecast) return undefined;
  const at = Date.parse(instant);
  for (const hour of forecast.hours) {
    const start = Date.parse(hour.time);
    if (at >= start && at < start + hour.stepHours * 3_600_000) {
      const cloudPercent = hour.cloudPercent;
      const verdict = cloudPercent <= CLEAR_UP_TO ? 'clear' : cloudPercent >= OVERCAST_FROM ? 'overcast' : 'partly';
      return { cloudPercent, verdict };
    }
  }
  return undefined;
}

export function skyLabel(verdict: Sky['verdict']): string {
  switch (verdict) {
    case 'clear': return $localize`:Cloud cover forecast at a pass:clear sky`;
    case 'partly': return $localize`:Cloud cover forecast at a pass:partly cloudy`;
    case 'overcast': return $localize`:Cloud cover forecast at a pass:overcast`;
  }
}

/** Where the table asks, rounded as the server will round it so that neighbours share. */
export function cloudsUrl(latitudeDeg: number, longitudeDeg: number): string {
  return `/api/weather/clouds?lat=${latitudeDeg.toFixed(1)}&lon=${longitudeDeg.toFixed(1)}`;
}
