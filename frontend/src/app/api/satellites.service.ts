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

/** A NORAD number as the pass endpoint accepts it, or undefined. */
export function asNoradId(text: string): number | undefined {
  const trimmed = text.trim();
  if (!/^\d{1,5}$/.test(trimmed)) return undefined;
  const id = Number(trimmed);
  return id >= 1 ? id : undefined;
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
