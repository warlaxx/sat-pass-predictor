import { PassesResponse } from './passes.model';

/** The query the user fills in. Mirrors the parameters of `GET /api/passes`. */
export interface PassQuery {
  readonly noradId: number;
  readonly lat: number;
  readonly lon: number;
  readonly alt: number;
  readonly hours: number;
  readonly minElevation: number;
}

/** Lyon, and the defaults the API itself applies. Same numbers, one source. */
export const DEFAULT_QUERY: PassQuery = {
  noradId: 25544,
  lat: 45.7578,
  lon: 4.832,
  alt: 170,
  hours: 48,
  minElevation: 10,
};

/**
 * The bound the backend enforces in `PassPredictionService`.
 *
 * Repeated here so the form can refuse before the round trip, exactly as the controller
 * annotation refuses before a propagation. Neither copy is the rule.
 */
export const MAX_WINDOW_HOURS = 240;

export function toParams(query: PassQuery): Record<string, number> {
  return { ...query };
}

/** True when the response is well formed but holds nothing to draw. */
export function isEmpty(response: PassesResponse | undefined): boolean {
  return response !== undefined && response.passes.length === 0;
}
