import { ChangeDetectionStrategy, Component, PLATFORM_ID, computed, effect, inject, input, signal } from '@angular/core';
import { DecimalPipe, isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { PassesResponse, ProblemDetail } from '../../api/passes.model';
import { DEFAULT_QUERY } from '../../api/passes.query';
import { asNoradId } from '../../api/satellites.service';
import { visibleWindow } from '../../calendar/ics';
import { Reveal } from '../../motion/reveal';
import { NextPass } from '../../next-pass/next-pass';
import { PassTable } from '../../pass-table/pass-table';
import { TleBanner } from '../../tle-banner/tle-banner';
import { CodeBlock } from '../../shared/code-block';
import { featuredSatellite } from '../../shared/featured';
import { requestPosition } from '../../shared/geolocation';
import { Seo, satelliteDescription, satelliteTitle } from '../../shared/seo';
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
  imports: [DecimalPipe, RouterLink, Reveal, NextPass, PassTable, TleBanner, CodeBlock],
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
  private readonly seo = inject(Seo);

  protected readonly windowHours = SATELLITE_WINDOW_HOURS;
  protected readonly id = computed(() => asNoradId(this.noradId()));
  /** The featured card's name and blurb: what a prerendered page carries before any call. */
  protected readonly featured = computed(() => featuredSatellite(this.id()));
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));
  protected readonly observer = signal({ lat: DEFAULT_QUERY.lat, lon: DEFAULT_QUERY.lon, alt: DEFAULT_QUERY.alt });
  protected readonly isDefaultObserver = computed(() =>
    this.observer().lat === DEFAULT_QUERY.lat && this.observer().lon === DEFAULT_QUERY.lon);

  protected readonly resource = httpResource<PassesResponse>(() => {
    const noradId = this.id();
    // Passes are the browser's to compute: prerendered at build time, they would be stale
    // before anyone read them.
    if (noradId === undefined || !this.browser) return undefined;
    const { lat, lon, alt } = this.observer();
    return {
      url: '/api/passes',
      params: { noradId, lat, lon, alt, hours: SATELLITE_WINDOW_HOURS, minElevation: DEFAULT_QUERY.minElevation },
    };
  });

  protected readonly response = computed(() => (this.resource.hasValue() ? this.resource.value() : undefined));

  /** The catalogue's name once known, the featured card's until then, the number otherwise. */
  protected readonly heading = computed(() => {
    const id = this.id();
    const name = this.response()?.satellite.name ?? this.featured()?.name;
    if (name) return name;
    return id === undefined ? $localize`Unknown satellite` : $localize`Satellite ${id}:noradId:`;
  });

  protected readonly problem = computed<ProblemDetail | undefined>(() => {
    const error = this.resource.error();
    if (!error) return undefined;
    const body = error instanceof HttpErrorResponse ? error.error : undefined;
    if (body && typeof body === 'object' && 'title' in body) return body as ProblemDetail;
    return { type: 'about:blank', title: $localize`The backend could not be reached`, status: 0, detail: $localize`The server may be starting. Please try again shortly.` };
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
    // A featured satellite already has its title from the route, and keeps it: the name
    // on its card reads better than the catalogue's "ISS (ZARYA)". Any other number gets
    // the catalogue's name once the response brings it.
    effect(() => {
      const name = this.response()?.satellite.name;
      if (name && !this.featured()) {
        this.seo.apply(this.router.url, { title: satelliteTitle(name), description: satelliteDescription(name) });
      }
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
