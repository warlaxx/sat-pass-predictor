import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { Title } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { PassesResponse, ProblemDetail } from '../../api/passes.model';
import { DEFAULT_QUERY } from '../../api/passes.query';
import { asNoradId } from '../../api/satellites.service';
import { visibleWindow } from '../../calendar/ics';
import { Reveal } from '../../motion/reveal';
import { PassTable } from '../../pass-table/pass-table';
import { TleBanner } from '../../tle-banner/tle-banner';
import { CodeBlock } from '../../shared/code-block';
import { requestPosition } from '../../shared/geolocation';
import { orbitFromTle, orbitRegime } from '../../shared/orbit';

/** Three days: enough to show a rhythm of passes, short enough to stay precise. */
export const SATELLITE_WINDOW_HOURS = 72;

/**
 * One satellite: its orbit, the freshness of its elements, and its next passes.
 *
 * One request, the same `/api/passes` the predictor makes, so the page costs the shared
 * budget exactly one call and every figure on it comes from the same elements. The orbit
 * summary is read off the TLE lines that response already carries.
 */
@Component({
  selector: 'app-satellite',
  imports: [DecimalPipe, RouterLink, Reveal, PassTable, TleBanner, CodeBlock],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './satellite.html',
  styles: `
    .observer { align-items: center; display: flex; flex-wrap: wrap; gap: 12px 20px; }
  `,
})
export class SatellitePage {
  /** The `:noradId` of the route, bound by the router. */
  readonly noradId = input.required<string>();

  private readonly router = inject(Router);
  private readonly title = inject(Title);

  protected readonly windowHours = SATELLITE_WINDOW_HOURS;
  protected readonly id = computed(() => asNoradId(this.noradId()));
  protected readonly observer = signal({ lat: DEFAULT_QUERY.lat, lon: DEFAULT_QUERY.lon, alt: DEFAULT_QUERY.alt });
  protected readonly isDefaultObserver = computed(() =>
    this.observer().lat === DEFAULT_QUERY.lat && this.observer().lon === DEFAULT_QUERY.lon);

  protected readonly resource = httpResource<PassesResponse>(() => {
    const noradId = this.id();
    if (noradId === undefined) return undefined;
    const { lat, lon, alt } = this.observer();
    return {
      url: '/api/passes',
      params: { noradId, lat, lon, alt, hours: SATELLITE_WINDOW_HOURS, minElevation: DEFAULT_QUERY.minElevation },
    };
  });

  protected readonly response = computed(() => (this.resource.hasValue() ? this.resource.value() : undefined));

  protected readonly problem = computed<ProblemDetail | undefined>(() => {
    const error = this.resource.error();
    if (!error) return undefined;
    const body = error instanceof HttpErrorResponse ? error.error : undefined;
    if (body && typeof body === 'object' && 'title' in body) return body as ProblemDetail;
    return { type: 'about:blank', title: 'The backend could not be reached', status: 0, detail: 'The server may be starting. Please try again shortly.' };
  });

  protected readonly orbit = computed(() => {
    const line2 = this.response()?.tle.line2;
    return line2 ? orbitFromTle(line2) : undefined;
  });
  protected readonly regime = computed(() => {
    const orbit = this.orbit();
    return orbit ? orbitRegime(orbit) : undefined;
  });
  protected readonly tleText = computed(() => {
    const response = this.response();
    return response ? `${response.satellite.name}\n${response.tle.line1}\n${response.tle.line2}` : '';
  });
  protected readonly visibleCount = computed(() =>
    (this.response()?.passes ?? []).filter((pass) => visibleWindow(pass) !== undefined).length);

  /** The same search, opened in the predictor with its globe and sky chart. */
  protected readonly predictorParams = computed(() => {
    const { lat, lon, alt } = this.observer();
    return { norad: this.id(), lat, lon, alt, hours: SATELLITE_WINDOW_HOURS, minEl: DEFAULT_QUERY.minElevation };
  });

  protected readonly locating = signal(false);
  protected readonly locationError = signal<string | undefined>(undefined);

  constructor() {
    effect(() => {
      const name = this.response()?.satellite.name;
      if (name) this.title.setTitle(`${name} passes · Sat Pass Predictor`);
    });
  }

  protected useMyPosition(): void {
    this.locationError.set(undefined);
    this.locating.set(true);
    requestPosition().then(
      (position) => this.observer.update((current) => ({
        lat: position.lat, lon: position.lon, alt: position.alt ?? current.alt,
      })),
      (error: Error) => this.locationError.set(error.message),
    ).finally(() => this.locating.set(false));
  }

  protected inspect(instant: string): void {
    void this.router.navigate(['/'], { queryParams: { ...this.predictorParams(), pass: instant } });
  }

  protected reload(): void {
    this.resource.reload();
  }
}
