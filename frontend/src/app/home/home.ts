import { ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, afterNextRender, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Discovery, Opportunity } from '../discovery/discovery';
import { ShareButton } from '../share/share-button';
import { SatellitePicker } from '../satellite-picker/satellite-picker';
import { Reveal } from '../motion/reveal';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { PassesApi } from '../api/passes.service';
import { PassQuery, DEFAULT_QUERY, MAX_FREQUENCY_MHZ, MAX_WINDOW_HOURS, MIN_FREQUENCY_MHZ } from '../api/passes.query';
import { PassDto, PassesResponse, ProblemDetail } from '../api/passes.model';
import { TleBanner } from '../tle-banner/tle-banner';
import { PassRibbon } from '../pass-ribbon/pass-ribbon';
import { PassViewer } from '../pass-viewer/pass-viewer';
import { PassTable } from '../pass-table/pass-table';
import { Globe } from '../globe/globe';
import { SkyPanorama } from '../sky-panorama/sky-panorama';
import { NextPass } from '../next-pass/next-pass';
import { PassProfile } from '../pass-profile/pass-profile';
import { Hero } from '../hero/hero';
import { LatestSeparations } from './latest-separations';
import { facingAzimuth } from '../sky-panorama/panorama-geometry';
import { groupIntoNights } from '../pass-ribbon/nights';
import { compassPoint, utcOffsetLabel } from '../format';
import { buildCalendar, saveCalendar } from '../calendar/ics';

import { requestPosition } from '../shared/geolocation';

/** The query-string names of a search, short enough to read in a shared link. */
const URL_FIELDS: Record<keyof PassQuery, string> = {
  noradId: 'norad', lat: 'lat', lon: 'lon', alt: 'alt', hours: 'hours', minElevation: 'minEl', frequencyMhz: 'freq',
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
  imports: [RouterLink, ShareButton, Discovery, SatellitePicker, Reveal, DatePipe, DecimalPipe, TleBanner, PassRibbon, PassTable, PassViewer, Globe, SkyPanorama, NextPass, PassProfile, Hero, LatestSeparations],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './home.scss',
  templateUrl: './home.html',
})
export class HomePage {
  private readonly api = inject(PassesApi);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  protected readonly maxHours = MAX_WINDOW_HOURS;
  protected readonly frequencyBounds = { min: MIN_FREQUENCY_MHZ, max: MAX_FREQUENCY_MHZ };
  protected readonly form = signal<PassQuery>(DEFAULT_QUERY);

  /**
   * The advanced settings, folded for a beginner (ABD-37). They open by themselves when
   * any of them is not the default, so a shared link never hides what it changed.
   */
  protected readonly advancedOpen = signal(false);

  /**
   * What the satellite field shows first: the default satellite by its name, so a
   * beginner reads "ISS (ZARYA)", not a catalogue number (ABD-37). A shared link's other
   * satellite keeps its number, the only name the page knows for it before a search.
   */
  protected readonly satelliteText = computed(() =>
    this.form().noradId === DEFAULT_QUERY.noradId ? DEFAULT_SATELLITE_NAME : String(this.form().noradId));

  /** The place in words: Lyon while it is the default, the coordinates once it is not. */
  protected readonly placeLabel = computed(() => {
    const { lat, lon } = this.form();
    if (lat === DEFAULT_QUERY.lat && lon === DEFAULT_QUERY.lon) {
      return $localize`:The default observer of the form:Lyon (default)`;
    }
    return `${lat.toFixed(4)}°, ${lon.toFixed(4)}°`;
  });
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
   * What the hero counts down to: the reader's own result, or until there is one the next
   * passes of the ISS over Lyon, asked for as the page opens (ABD-31). Those stay out of
   * the console: the reader has computed nothing yet, and the page below says so.
   */
  private readonly featured = this.api.featured;
  protected readonly heroResponse = computed(() =>
    this.response() ?? (this.featured.hasValue() ? this.featured.value() : undefined));
  /** A shared link opens on its own result, and the featured passes are not asked for. */
  private readonly linked: boolean;
  protected readonly featuredPending = computed(() =>
    !this.linked && !this.featured.hasValue() && !this.featured.error());

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
      title: $localize`The backend could not be reached`,
      status: 0,
      detail: $localize`The server may be starting. Please try again shortly.`,
    };
  });

  protected readonly nightCount = computed(() => groupIntoNights(this.response()?.passes ?? []).length);

  /** The four views of a result, as the page introduces them below the console. */
  protected readonly features = [
    { title: $localize`Sky chart`, text: $localize`The pass drawn across your horizon, with the elevation threshold and the direction to face.` },
    { title: $localize`Globe`, text: $localize`Ground track coloured by sunlight on the satellite, the visibility circle and the imaging swath.` },
    { title: $localize`Elevation profile`, text: $localize`Elevation and range over time, with the Doppler shift when you give a downlink frequency.` },
    { title: $localize`Pass list`, text: $localize`Every pass in the window as a table, exportable to CSV and to your calendar as .ics.` },
  ] as const;

  // --- Page sections ------------------------------------------------------

  protected readonly sections = [
    { id: 'passes', label: $localize`Passes` },
    { id: 'globe', label: $localize`Globe` },
    { id: 'sky', label: $localize`Sky chart` },
    { id: 'table', label: $localize`Table` },
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
    this.linked = linked !== undefined;
    if (linked) {
      this.form.set(linked);
      this.advancedOpen.set(hasAdvancedSettings(linked));
      this.api.search(linked);
      const pass = params.get('pass');
      if (pass) this.selected.set(pass);
    }

    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      // In the browser only: the prerendered HTML must not carry a pass that would be
      // stale by the time anyone reads it.
      if (!this.linked) this.api.loadFeatured();

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
  protected readonly calendarHint = computed(() => this.calendar()
    ? $localize`One event per potentially visible pass, with a reminder 10 minutes before`
    : $localize`No pass in this window has a favourable sample`);

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
  private readonly locationError = signal<string | undefined>(undefined);
  /** Where the position was asked for, so that a failure is said there and nowhere else. */
  private readonly locatedFrom = signal<'console' | 'hero'>('console');
  protected readonly consoleLocationError = computed(() =>
    this.locatedFrom() === 'console' ? this.locationError() : undefined);
  protected readonly heroLocationError = computed(() =>
    this.locatedFrom() === 'hero' ? this.locationError() : undefined);

  /**
   * Fills the position from the browser, and never becomes the only way to give one.
   *
   * <p>Geolocation is a permission the user can refuse, a sensor that can fail and a call
   * that can hang. Each of those has a message here rather than a silent no-op, and the
   * two fields stay editable throughout: a refused prompt must not cost the user the
   * page.
   *
   * <p>With a result already on screen, the position is computed for at once. The pass
   * globe draws the observer of the response, and only a new response can put its dot on
   * the located position without leaving the track and the sight line at the old one.
   * From the hero's card it always computes: that button promises the next pass over
   * the reader's own sky in one click, not a filled form further down.
   */
  protected useMyPosition(from: 'console' | 'hero' = 'console'): void {
    this.locatedFrom.set(from);
    this.locationError.set(undefined);
    this.locating.set(true);
    requestPosition().then(
      (position) => {
        this.form.update((query) => ({
          ...query,
          lat: position.lat,
          lon: position.lon,
          alt: position.alt ?? query.alt,
        }));
        // A measured altitude lands in a folded field: show it rather than compute with it unseen.
        if (hasAdvancedSettings(this.form())) this.advancedOpen.set(true);
        if ((from === 'hero' || this.searched()) && this.satelliteResolved()) this.search();
      },
      (error: Error) => this.locationError.set(error.message),
    ).finally(() => this.locating.set(false));
  }

  protected patch(field: keyof PassQuery, value: string): void {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return;
    this.form.update((query) => ({ ...query, [field]: parsed }));
  }

  /** An emptied field means "no frequency", not zero: the API refuses zero, and so should the form. */
  protected setFrequency(value: string): void {
    const trimmed = value.trim();
    if (trimmed === '') {
      this.form.update(({ frequencyMhz: _, ...query }) => query);
      return;
    }
    const parsed = Number(trimmed);
    if (Number.isFinite(parsed)) this.form.update((query) => ({ ...query, frequencyMhz: parsed }));
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
      this.satelliteError.set($localize`Choose a satellite from the suggestions, or type its NORAD number.`);
      return;
    }
    this.discovered.set(undefined);
    this.selected.set(undefined);
    this.api.search(this.form());
    this.writeUrl(this.form());
  }

  /**
   * The hero's call to action: the same computation as the console's button, then the
   * console in view, where the progress, a refused satellite or the result appear.
   */
  protected computeFromHero(): void {
    this.search();
    document.getElementById('query')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /**
   * A link that reopens this very pass (ABD-33): the satellite, place and threshold of
   * the result on screen - which may come from the discovery list rather than the form -
   * the window of the form, and the pass itself. Nothing but the coordinates chosen.
   */
  protected shareUrl(pass: PassDto): string {
    const loaded = this.response();
    const url = new URL(this.document.location.href);
    url.search = '';
    url.hash = '';
    if (loaded) {
      const query: PassQuery = {
        ...this.form(),
        noradId: loaded.satellite.noradId,
        lat: loaded.observer.latitudeDeg,
        lon: loaded.observer.longitudeDeg,
        alt: loaded.observer.altitudeM,
        minElevation: loaded.minElevationDeg,
      };
      for (const [field, name] of Object.entries(URL_FIELDS)) {
        const value = query[field as keyof PassQuery];
        if (value !== undefined) url.searchParams.set(name, String(value));
      }
    }
    url.searchParams.set('pass', pass.aos.instant);
    return url.toString();
  }

  /** What a share sheet shows above the link: the satellite and when it passes. */
  protected shareTitle(pass: PassDto): string {
    const name = this.response()?.satellite.name ?? '';
    const when = new Date(pass.aos.instant).toLocaleString(this.document.documentElement.lang || undefined,
      { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
    return $localize`:Title of a shared pass:${name}:satellite: passes over me on ${when}:when:`;
  }

  /** The URL of the result on screen, so that copying the address shares the result. */
  private writeUrl(query: PassQuery): void {
    const queryParams = Object.fromEntries(
      Object.entries(URL_FIELDS).map(([field, name]) => [name, query[field as keyof PassQuery]]),
    );
    // 'manual': the router's scroll restoration would otherwise send the reader back to
    // the top of the page, away from the button they just pressed.
    void this.router.navigate([], { queryParams, replaceUrl: true, scroll: 'manual' });
  }

  protected reload(): void {
    this.api.reload();
  }
}

/** The name CelesTrak gives the default satellite, as the hero's card writes it. */
const DEFAULT_SATELLITE_NAME = 'ISS (ZARYA)';

/** True when a query sets anything the advanced settings hold, besides the place. */
export function hasAdvancedSettings(query: PassQuery): boolean {
  return query.alt !== DEFAULT_QUERY.alt
    || query.hours !== DEFAULT_QUERY.hours
    || query.minElevation !== DEFAULT_QUERY.minElevation
    || query.frequencyMhz !== undefined;
}
