import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, forkJoin, map, of } from 'rxjs';
import { PassDto, PassesResponse } from '../api/passes.model';
import { PassQuery, toParams } from '../api/passes.query';

export interface Opportunity {
  response: PassesResponse;
  pass: PassDto;
  start: string;
  end: string;
}
interface Search { query: PassQuery; ids: number[] }
interface Result { noradId: number; response?: PassesResponse; error?: string }

export function parseSatellites(text: string): number[] {
  const tokens = text.trim().split(/[\s,]+/);
  if (tokens.some(token => !/^\d{1,5}$/.test(token) || Number(token) < 1)) {
    throw new Error('Enter NORAD numbers from 1 to 99999, separated by commas.');
  }
  const ids = [...new Set(tokens.map(Number))];
  if (ids.length > 5) throw new Error('Search up to five distinct satellites at a time.');
  return ids;
}

/** First consecutive run of favourable samples, ranked by visibility onset, not AOS. */
export function nextOpportunity(response: PassesResponse): Opportunity | undefined {
  const candidates = response.passes.flatMap(pass => {
    const first = pass.track.findIndex(point => point.visible);
    if (first < 0) return [];
    let last = first;
    while (last + 1 < pass.track.length && pass.track[last + 1].visible) last++;
    return [{ response, pass, start: pass.track[first].instant, end: pass.track[last].instant }];
  });
  return candidates.sort((a, b) => Date.parse(a.start) - Date.parse(b.start))[0];
}

@Component({
  selector: 'app-discovery',
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './discovery.html',
  styleUrl: './discovery.scss',
})
export class Discovery {
  readonly query = input.required<PassQuery>();
  readonly open = output<Opportunity>();
  protected readonly toMillis = Date.parse;
  protected readonly ids = signal('25544, 48274');
  protected readonly validation = signal('');
  private readonly http = inject(HttpClient);
  protected readonly search = signal<Search | undefined>(undefined);
  protected readonly results = rxResource({
    params: () => this.search(),
    stream: ({ params }) => forkJoin(params.ids.map(noradId =>
      this.http.get<PassesResponse>('/api/passes', {
        params: toParams({ ...params.query, noradId, hours: 168 }),
      }).pipe(
        map(response => ({ noradId, response } as Result)),
        catchError((error: HttpErrorResponse) => of<Result>({ noradId,
          error: typeof error.error?.detail === 'string' ? error.error.detail :
            'Could not retrieve a prediction. Try this satellite again.',
        })),
      ),
    )),
  });
  protected readonly values = computed(() => this.results.hasValue() ? this.results.value() : []);
  protected readonly opportunities = computed(() => this.values()
    .flatMap(result => result.response ? [nextOpportunity(result.response)].filter((x): x is Opportunity => !!x) : [])
    .sort((a, b) => Date.parse(a.start) - Date.parse(b.start)));
  protected readonly unavailable = computed(() => this.values().filter(result => result.error));
  protected readonly empty = computed(() => this.values().filter(result => result.response && !nextOpportunity(result.response)));

  protected find(): void {
    this.validation.set('');
    try {
      const ids = parseSatellites(this.ids());
      const query = { ...this.query() };
      const bounds: [number, number, number][] = [
        [query.lat, -90, 90], [query.lon, -180, 180], [query.alt, -500, 9000], [query.minElevation, 0, 89],
      ];
      if (bounds.some(([value, min, max]) => !Number.isFinite(value) || value < min || value > max)) {
        throw new Error('Correct the observer position and elevation threshold above before searching.');
      }
      this.search.set({ query, ids });
    } catch (error) {
      this.validation.set((error as Error).message);
    }
  }
}
