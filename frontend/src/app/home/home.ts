import { ChangeDetectionStrategy, Component, DestroyRef, afterNextRender, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Discovery, Opportunity } from '../discovery/discovery';
import { SatellitePicker } from '../satellite-picker/satellite-picker';
import { Reveal } from '../motion/reveal';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { PassesApi } from '../api/passes.service';
import { PassQuery, DEFAULT_QUERY, MAX_WINDOW_HOURS } from '../api/passes.query';
import { PassDto, PassesResponse, ProblemDetail } from '../api/passes.model';
import { TleBanner } from '../tle-banner/tle-banner';
import { PassRibbon } from '../pass-ribbon/pass-ribbon';
import { PassViewer } from '../pass-viewer/pass-viewer';
import { PassTable } from '../pass-table/pass-table';
import { Globe } from '../globe/globe';
import { SkyPanorama } from '../sky-panorama/sky-panorama';
import { facingAzimuth } from '../sky-panorama/panorama-geometry';
import { groupIntoNights } from '../pass-ribbon/nights';
import { compassPoint, utcOffsetLabel } from '../format';
import { buildCalendar, saveCalendar } from '../calendar/ics';

import { requestPosition } from '../shared/geolocation';

/** The query-string names of a search, short enough to read in a shared link. */
const URL_FIELDS: Record<keyof PassQuery, string> = {
  noradId: 'norad', lat: 'lat', lon: 'lon', alt: 'alt', hours: 'hours', minElevation: 'minEl',
};

/**
 * The search a link carries, over the defaults; undefined when it names no satellite.
 *
 * A field that is not a finite number is ignored rather than refused: a hand-edited link
 * should still open on something, and the form's own bounds judge what is left.
 */
export function queryFromUrl(params: { get(name: string): string | null }): PassQuery | undefined {
  if (params.get(URL_FIELDS.noradId) === null) return undefined;
  const query: Record<string, number> = { ...DEFAULT_QUERY };
  for (const [field, name] of Object.entries(URL_FIELDS)) {
    const raw = params.get(name);
    if (raw !== null && raw.trim() !== '' && Number.isFinite(Number(raw))) query[field] = Number(raw);
  }
  return query as unknown as PassQuery;
}

/**
 * The predictor: the query, the selected pass and nothing else.
 *
 * It owns the query, the selected pass and nothing else. The four states of the request
 * are read off the resource rather than tracked here, and each one is drawn: a page that
 * shows the same spinner for "still loading", "the server said no" and "no pass in this
 * window" is a page that answers the wrong question three times.
 */
@Component({
  selector: 'app-home',
  imports: [RouterLink, Discovery, SatellitePicker, Reveal, DatePipe, DecimalPipe, TleBanner, PassRibbon, PassTable, PassViewer, Globe, SkyPanorama],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './home.scss',
  templateUrl: './home.html',
})
export class HomePage {
  private readonly api = inject(PassesApi);
  private readonly router = inject(Router);

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
    // A shared link opens on its own result. Read once: later searches write the URL,
    // they do not come back through it.
    const params = inject(ActivatedRoute).snapshot.queryParamMap;
    const linked = queryFromUrl(params);
    if (linked) {
      this.form.set(linked);
      this.api.search(linked);
      const pass = params.get('pass');
      if (pass) this.selected.set(pass);
    }

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

  // --- Calendar export ----------------------------------------------------

  /** The .ics of the potentially visible passes on screen; undefined when there is none. */
  protected readonly calendar = computed(() => {
    const response = this.response();
    return response ? buildCalendar(response) : undefined;
  });

  protected downloadCalendar(): void {
    const response = this.response();
    if (response) saveCalendar(response);
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
    this.locating.set(true);
    requestPosition().then(
      (position) => this.form.update((query) => ({
        ...query,
        lat: position.lat,
        lon: position.lon,
        alt: position.alt ?? query.alt,
      })),
      (error: Error) => this.locationError.set(error.message),
    ).finally(() => this.locating.set(false));
  }

  protected patch(field: keyof PassQuery, value: string): void {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return;
    this.form.update((query) => ({ ...query, [field]: parsed }));
  }

  // --- Satellite, by name or number --------------------------------------

  /** False while the field holds a name that has not been matched to a number yet. */
  protected readonly satelliteResolved = signal(true);
  protected readonly satelliteError = signal<string | undefined>(undefined);

  protected setSatellite(noradId: number | null): void {
    this.satelliteError.set(undefined);
    this.satelliteResolved.set(noradId !== null);
    if (noradId !== null) this.form.update((query) => ({ ...query, noradId }));
  }

  protected inspect(opportunity: Opportunity): void {
    this.discovered.set(opportunity.response);
    this.selected.set(opportunity.pass.aos.instant);
  }

  protected search(): void {
    // Computing with the previous number while the field shows another name would draw
    // the passes of a satellite the user did not ask for, under the one they did.
    if (!this.satelliteResolved()) {
      this.satelliteError.set('Choose a satellite from the suggestions, or type its NORAD number.');
      return;
    }
    this.discovered.set(undefined);
    this.selected.set(undefined);
    this.api.search(this.form());
    this.writeUrl(this.form());
  }

  /** The URL of the result on screen, so that copying the address shares the result. */
  private writeUrl(query: PassQuery): void {
    const queryParams = Object.fromEntries(
      Object.entries(URL_FIELDS).map(([field, name]) => [name, query[field as keyof PassQuery]]),
    );
    void this.router.navigate([], { queryParams, replaceUrl: true });
  }

  protected reload(): void {
    this.api.reload();
  }
}
