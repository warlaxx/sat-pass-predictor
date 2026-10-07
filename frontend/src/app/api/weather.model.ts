/**
 * The shape of `GET /api/weather/clouds` (ABD-36): MET Norway's cloud cover over a cell of
 * a tenth of a degree, kept by the server until MET's own `Expires`.
 */
export interface CloudHourDto {
  /** ISO-8601 UTC: the start of the step. */
  readonly time: string;
  /** Share of the sky covered, all cloud levels together, 0 to 100. */
  readonly cloudPercent: number;
  /** How long the value holds: 1 for the first days, then 6. */
  readonly stepHours: number;
}

export interface CloudForecastDto {
  readonly latitudeDeg: number;
  readonly longitudeDeg: number;
  readonly updatedAt: string | null;
  readonly hours: readonly CloudHourDto[];
}
