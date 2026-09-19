import { ChangeDetectionStrategy, Component, DestroyRef, afterNextRender, computed, inject, signal } from '@angular/core';
import { Discovery, Opportunity } from './discovery/discovery';
import { Reveal } from './motion/reveal';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { PassesApi } from './api/passes.service';
import { PassQuery, DEFAULT_QUERY, MAX_WINDOW_HOURS } from './api/passes.query';
import { PassDto, PassesResponse, ProblemDetail } from './api/passes.model';
import { TleBanner } from './tle-banner/tle-banner';
import { PassRibbon } from './pass-ribbon/pass-ribbon';
import { PassViewer } from './pass-viewer/pass-viewer';
import { PassTable } from './pass-table/pass-table';
import { Globe } from './globe/globe';
import { SkyPanorama } from './sky-panorama/sky-panorama';
import { facingAzimuth } from './sky-panorama/panorama-geometry';
import { groupIntoNights } from './pass-ribbon/nights';
import { compassPoint, utcOffsetLabel } from './format';

const GEOLOCATION_ERRORS: Record<number, string> = {
  1: 'Permission refused. Type the position in instead.',
  2: 'Your device could not determine a position.',
  3: 'The position request timed out.',
};

function round(value: number, decimals: number): number {
  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
}

/**
 * The page.
 *
 * It owns the query, the selected pass and nothing else. The four states of the request
 * are read off the resource rather than tracked here, and each one is drawn: a page that
 * shows the same spinner for "still loading", "the server said no" and "no pass in this
 * window" is a page that answers the wrong question three times.
 */
@Component({
  selector: 'app-root',
  imports: [Discovery, Reveal, DatePipe, DecimalPipe, TleBanner, PassRibbon, PassTable, PassViewer, Globe, SkyPanorama],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  private readonly api = inject(PassesApi);

  protected readonly maxHours = MAX_WINDOW_HOURS;
  protected readonly form = signal<PassQuery>(DEFAULT_QUERY);
  protected readonly selected = signal<string | undefined>(undefined);

  protected readonly resource = this.api.resource;
  private readonly discovered = signal<PassesResponse | undefined>(undefined);
  protected readonly loading = computed(() => !this.discovered() && this.resource.isLoading());
  protected readonly response = computed(() => this.discovered() ?? (this.resource.hasValue() ? this.resource.value() : undefined));
  protected readonly selectedPass = computed(() => {
    const passes = this.response()?.passes ?? [];
    return passes.find(pass => pass.aos.instant === this.selected()) ?? passes[0];
  });
  protected readonly searched = computed(() => !!this.discovered() || this.api.lastQuery() !== undefined);

  /**
   * The error, as the API means it to be read.
   *
   * The body of a failure is a Problem Detail, so `type` is what distinguishes an
   * unknown satellite from a CelesTrak outage - two answers that share status 503 have
   * nothing else to tell them apart. A transport failure has no body at all, hence the
   * fallback sentence.
   */
  protected readonly problem = computed<ProblemDetail | undefined>(() => {
    if (this.discovered()) return undefined;
    const error = this.resource.error();
    if (!error) return undefined;
    const body = error instanceof HttpErrorResponse ? error.error : undefined;
    if (body && typeof body === 'object' && 'title' in body) {
      return body as ProblemDetail;
    }
    return {
      type: 'about:blank',
      title: 'The backend could not be reached',
      status: 0,
      detail: 'The server may be starting. Please try again shortly.',
    };
  });

  protected readonly nightCount = computed(() => groupIntoNights(this.response()?.passes ?? []).length);

  // --- Page sections ------------------------------------------------------

  protected readonly sections = [
    { id: 'passes', label: 'Passes' },
    { id: 'globe', label: 'Globe' },
    { id: 'sky', label: 'Sky chart' },
    { id: 'table', label: 'Table' },
  ] as const;

  /**
   * The section under the reader's eye, for the underline in the header.
   *
   * A scroll listener rather than an IntersectionObserver per section: the sections come
   * and go with the result, and "the last one whose top has passed a third of the
   * viewport" is one loop over four elements.
   */
  protected readonly activeSection = signal<string>('passes');

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const update = (): void => {
        let current: string = this.sections[0].id;
        for (const section of this.sections) {
          const element = document.getElementById(section.id);
          if (element && element.getBoundingClientRect().top < window.innerHeight / 3) current = section.id;
        }
        this.activeSection.set(current);
      };
      window.addEventListener('scroll', update, { passive: true });
      destroyRef.onDestroy(() => window.removeEventListener('scroll', update));
    });
  }

  protected facing(pass: PassDto): string {
    return compassPoint(facingAzimuth(pass.track.length ? pass.track : [pass.aos, pass.culmination, pass.los]));
  }

  protected zone(pass: PassDto): string {
    return utcOffsetLabel(pass.aos.instant);
  }

  /**
   * How old the elements will be when this pass rises: the age the server measured, plus
   * the wait until the pass. A pass eight days out is predicted from elements eight days
   * older than the banner says, and its times are worth that much less.
   */
  protected ageAtRise(pass: PassDto): number | undefined {
    const response = this.response();
    if (!response) return undefined;
    const wait = (Date.parse(pass.aos.instant) - Date.parse(response.computedAt)) / 1000;
    return response.tle.ageSeconds + Math.max(0, wait);
  }

  protected readonly isEmptyResult = computed(() => {
    const response = this.response();
    return response !== undefined && response.passes.length === 0;
  });

  // --- Browser geolocation -------------------------------------------------

  protected readonly locating = signal(false);
  protected readonly locationError = signal<string | undefined>(undefined);

  /**
   * Fills the position from the browser, and never becomes the only way to give one.
   *
   * <p>Geolocation is a permission the user can refuse, a sensor that can fail and a call
   * that can hang. Each of those has a message here rather than a silent no-op, and the
   * two fields stay editable throughout: a refused prompt must not cost the user the
   * page.
   */
  protected useMyPosition(): void {
    this.locationError.set(undefined);

    if (!('geolocation' in navigator)) {
      this.locationError.set('This browser does not offer geolocation. Type the position in.');
      return;
    }

    this.locating.set(true);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.form.update((query) => ({
          ...query,
          // Four decimals is about eleven metres. An observer a hundred metres off
          // changes nothing in a pass four hundred kilometres up, and the extra digits
          // would only put a more precise home address on screen.
          lat: round(position.coords.latitude, 4),
          lon: round(position.coords.longitude, 4),
          // The Geolocation API returns altitude above the WGS84 ellipsoid, which is
          // exactly the datum ObserverLocation expects - no conversion, and no guess
          // when the device does not provide one.
          alt: position.coords.altitude === null
            ? query.alt
            : Math.round(position.coords.altitude),
        }));
        this.locating.set(false);
      },
      (error) => {
        this.locationError.set(GEOLOCATION_ERRORS[error.code] ?? 'Position unavailable.');
        this.locating.set(false);
      },
      {
        // Metres are pointless here and a GPS fix costs battery and seconds: the coarse
        // network position is already far below the accuracy this computation needs.
        enableHighAccuracy: false,
        timeout: 10_000,
        maximumAge: 300_000,
      },
    );
  }

  protected patch(field: keyof PassQuery, value: string): void {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return;
    this.form.update((query) => ({ ...query, [field]: parsed }));
  }

  protected inspect(opportunity: Opportunity): void {
    this.discovered.set(opportunity.response);
    this.selected.set(opportunity.pass.aos.instant);
  }

  protected search(): void {
    this.discovered.set(undefined);
    this.selected.set(undefined);
    this.api.search(this.form());
  }

  protected reload(): void {
    this.api.reload();
  }
}
