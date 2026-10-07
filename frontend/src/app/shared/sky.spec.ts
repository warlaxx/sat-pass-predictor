import { describe, expect, it } from 'vitest';
import { CloudForecastDto } from '../api/weather.model';
import { cloudsUrl, skyAt } from './sky';

const FORECAST: CloudForecastDto = {
  latitudeDeg: 45.8,
  longitudeDeg: 4.8,
  updatedAt: '2026-10-07T09:21:33Z',
  hours: [
    { time: '2026-10-07T20:00:00Z', cloudPercent: 10, stepHours: 1 },
    { time: '2026-10-07T21:00:00Z', cloudPercent: 50, stepHours: 1 },
    { time: '2026-10-07T22:00:00Z', cloudPercent: 25, stepHours: 1 },
    { time: '2026-10-10T00:00:00Z', cloudPercent: 95, stepHours: 6 },
  ],
};

describe('skyAt', () => {
  it('reads the step that holds at the peak, in oktas', () => {
    expect(skyAt(FORECAST, '2026-10-07T20:59:59Z')).toEqual({ cloudPercent: 10, verdict: 'clear' });
    expect(skyAt(FORECAST, '2026-10-07T21:00:00Z')).toEqual({ cloudPercent: 50, verdict: 'partly' });
    expect(skyAt(FORECAST, '2026-10-07T22:30:00Z')).toEqual({ cloudPercent: 25, verdict: 'clear' });
    expect(skyAt(FORECAST, '2026-10-10T05:12:00Z')).toEqual({ cloudPercent: 95, verdict: 'overcast' });
  });

  it('says nothing outside the forecast rather than guess', () => {
    expect(skyAt(FORECAST, '2026-10-07T19:59:00Z')).toBeUndefined();
    // A gap between the hourly steps and the six-hourly ones.
    expect(skyAt(FORECAST, '2026-10-08T12:00:00Z')).toBeUndefined();
    expect(skyAt(FORECAST, '2026-10-10T06:00:00Z')).toBeUndefined();
    expect(skyAt(undefined, '2026-10-07T21:00:00Z')).toBeUndefined();
  });

  it('asks for the cell the server keeps', () => {
    expect(cloudsUrl(45.7578, 4.832)).toBe('/api/weather/clouds?lat=45.8&lon=4.8');
    expect(cloudsUrl(-33.92, 18.42)).toBe('/api/weather/clouds?lat=-33.9&lon=18.4');
  });
});
