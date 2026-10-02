import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** One entry of `GET /api/satellites`: the name CelesTrak publishes, and its number. */
export interface SatelliteMatch {
  readonly noradId: number;
  readonly name: string;
}

export interface SatelliteSearchResponse {
  readonly results: SatelliteMatch[];
  readonly catalogFetchedAt: string;
}

/**
 * One entry of `GET /api/satellites/launches`: a launch (`2026-045`), how many of its
 * satellites the catalogue holds, and the lowest-numbered one, which stands for the group.
 */
export interface Launch {
  readonly designator: string;
  readonly satellites: number;
  readonly noradId: number;
  readonly name: string;
}

export interface LaunchesResponse {
  readonly launches: Launch[];
  readonly catalogFetchedAt: string;
}

/**
 * The highest NORAD number the pass endpoint accepts: Z9999 in Alpha-5, the last a TLE
 * line can carry. Numbers above 99999 have been given since July 2026.
 */
export const MAX_NORAD_ID = 339999;

/** A NORAD number as the pass endpoint accepts it, or undefined. */
export function asNoradId(text: string): number | undefined {
  const trimmed = text.trim();
  if (!/^\d{1,6}$/.test(trimmed)) return undefined;
  const id = Number(trimmed);
  return id >= 1 && id <= MAX_NORAD_ID ? id : undefined;
}

/**
 * Name lookup. A plain Observable rather than a resource: the picker debounces and
 * cancels its own requests, and a resource would re-fetch on every keystroke.
 */
@Injectable({ providedIn: 'root' })
export class SatellitesApi {
  private readonly http = inject(HttpClient);

  search(q: string, limit = 8): Observable<SatelliteSearchResponse> {
    return this.http.get<SatelliteSearchResponse>('/api/satellites', { params: { q, limit } });
  }
}
