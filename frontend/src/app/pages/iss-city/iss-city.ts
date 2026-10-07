import { ChangeDetectionStrategy, Component, PLATFORM_ID, computed, inject, input } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { PassesResponse, ProblemDetail } from '../../api/passes.model';
import { DEFAULT_QUERY } from '../../api/passes.query';
import { visibleWindow } from '../../calendar/ics';
import { Reveal } from '../../motion/reveal';
import { NextPass } from '../../next-pass/next-pass';
import { PassTable } from '../../pass-table/pass-table';
import { ISS_CITIES, cityFaq, cityHeading, cityIntro, cityName, cityOf, issCity } from './iss-cities';

/** The window `/api/featured-pass` computes: the home page's, two days. */
const WINDOW_HOURS = 48;

/**
 * The ISS over one city (ABD-34): what passes are like there, its next passes, and the
 * questions a search for "ISS tonight Paris" brings.
 *
 * Prerendered with its heading, intro and answers; the passes come from
 * `/api/featured-pass?city=` in the browser, unmetered and cached like the home page's,
 * so a visit never spends the shared demo quota.
 */
@Component({
  selector: 'app-iss-city',
  imports: [RouterLink, Reveal, NextPass, PassTable],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './iss-city.html',
  styleUrl: './iss-city.scss',
})
export class IssCityPage {
  private readonly router = inject(Router);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));

  /** The `:city` of the route, bound by the router. */
  readonly city = input.required<string>();

  protected readonly place = computed(() => issCity(this.city()));
  protected readonly name = computed(() => {
    const place = this.place();
    return place ? cityName(place) : '';
  });
  /** "Paris", or in French "de Paris", for the sentences that say "over" the city. */
  protected readonly over = computed(() => {
    const place = this.place();
    return place ? cityOf(place) : '';
  });
  protected readonly heading = computed(() => {
    const place = this.place();
    return place ? cityHeading(place) : $localize`:Heading of an unknown ISS city:No page for this city`;
  });
  protected readonly intro = computed(() => {
    const place = this.place();
    return place ? cityIntro(place) : '';
  });
  protected readonly faq = computed(() => {
    const place = this.place();
    return place ? cityFaq(place) : [];
  });
  /** Every other city, for the links at the foot of the page. */
  protected readonly others = computed(() =>
    ISS_CITIES.filter((other) => other.slug !== this.city())
      .map((other) => ({ slug: other.slug, name: cityName(other) }))
      .sort((a, b) => a.name.localeCompare(b.name)));

  protected readonly resource = httpResource<PassesResponse>(() => {
    const place = this.place();
    // Never at build time: a prerendered pass would be stale before anyone read it.
    if (!place || !this.browser) return undefined;
    return { url: '/api/featured-pass', params: { city: place.slug } };
  });
  protected readonly response = computed(() => (this.resource.hasValue() ? this.resource.value() : undefined));
  protected readonly visibleCount = computed(() =>
    (this.response()?.passes ?? []).filter((pass) => visibleWindow(pass) !== undefined).length);
  protected readonly windowHours = WINDOW_HOURS;

  protected readonly problem = computed<ProblemDetail | undefined>(() => {
    const error = this.resource.error();
    if (!error) return undefined;
    const body = error instanceof HttpErrorResponse ? error.error : undefined;
    if (body && typeof body === 'object' && 'title' in body) return body as ProblemDetail;
    return { type: 'about:blank', title: $localize`The backend could not be reached`, status: 0, detail: $localize`The server may be starting. Please try again shortly.` };
  });

  /** The same passes, opened in the predictor with its globe and sky chart. */
  protected readonly predictorParams = computed(() => {
    const place = this.place();
    return place
      ? { norad: 25544, lat: place.lat, lon: place.lon, alt: place.alt, hours: WINDOW_HOURS, minEl: DEFAULT_QUERY.minElevation }
      : {};
  });

  protected inspect(instant: string): void {
    void this.router.navigate(['/'], { queryParams: { ...this.predictorParams(), pass: instant } });
  }

  protected reload(): void {
    this.resource.reload();
  }
}
