/**
 * The shape of `GET /api/passes`, written by hand.
 *
 * Generating these types from `/v3/api-docs` was the alternative. It would remove the
 * risk of drift and add a build step, a generator to keep up to date and a pile of
 * generated code to review. For a contract of six records the trade is not worth it —
 * provided the drift is caught rather than hoped away, which is what
 * `passes.model.spec.ts` is for: it parses a response captured from the real API and
 * fails to compile, or to run, the day a field moves.
 *
 * Every instant is an ISO-8601 UTC string. The server settles time zones by refusing to
 * have an opinion; the browser is the only place that has one.
 */

/** One point of the sampled track: the satellite seen from the ground and from space. */
export interface TrackPointDto {
  readonly instant: string;
  readonly azimuthDeg: number;
  readonly elevationDeg: number;
  readonly rangeKm: number;
  readonly subPoint: SubPointDto;
  /** Entire solar disc clear of Earth, excluding penumbra. */
  readonly illuminated: boolean;
  /** Sunlit with observer Sun elevation ≤ -6°; not a brightness/weather guarantee. */
  readonly visible: boolean;
}

export interface SubPointDto {
  readonly latitudeDeg: number;
  readonly longitudeDeg: number;
  readonly altitudeKm: number;
}

/** One of the three labelled instants of a pass. Also a point of its track. */
export interface PhaseDto {
  readonly instant: string;
  readonly azimuthDeg: number;
  readonly elevationDeg: number;
  readonly rangeKm: number;
}

export interface PassDto {
  readonly aos: PhaseDto;
  readonly culmination: PhaseDto;
  readonly los: PhaseDto;
  readonly durationSeconds: number;
  readonly track: readonly TrackPointDto[];
}

export interface SatelliteDto {
  readonly noradId: number;
  readonly name: string;
}

/**
 * The elements that actually served the computation.
 *
 * `ageSeconds` is the age since the **epoch** of the elements, computed by the server.
 * It is the physical age, the one SGP4's error grows with; the age since retrieval is an
 * operational concern the API does not publish. A client recomputing this from `epoch`
 * and its own clock would show a false age the moment that clock drifts.
 */
export interface TleDto {
  readonly epoch: string;
  readonly ageSeconds: number;
  readonly source: string;
  readonly fetchedAt: string;
  readonly line1: string;
  readonly line2: string;
}

export interface ObserverDto {
  readonly latitudeDeg: number;
  readonly longitudeDeg: number;
  readonly altitudeM: number;
}

export interface PassesResponse {
  readonly satellite: SatelliteDto;
  readonly tle: TleDto;
  readonly observer: ObserverDto;
  readonly minElevationDeg: number;
  readonly computedAt: string;
  readonly passes: readonly PassDto[];
}

/**
 * RFC 9457 Problem Details, the single error format of this API.
 *
 * `type` is the stable part: it is what tells "this satellite does not exist" from
 * "CelesTrak is down" without parsing a human sentence. Two of these share status 503.
 */
export interface ProblemDetail {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
}
