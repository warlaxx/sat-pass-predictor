import { ChangeDetectionStrategy, Component, LOCALE_ID, PLATFORM_ID, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe, isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { PassesResponse, ProblemDetail } from '../../api/passes.model';
import { DEFAULT_QUERY } from '../../api/passes.query';
import { asNoradId } from '../../api/satellites.service';
import { SeparationEvent, SpaceObject } from '../../api/separations.model';
import { visibleWindow } from '../../calendar/ics';
import { Reveal } from '../../motion/reveal';
import { NextPass } from '../../next-pass/next-pass';
import { PassTable } from '../../pass-table/pass-table';
import { requestPosition } from '../../shared/geolocation';
import { Seo } from '../../shared/seo';
import {
  isGeostationary, orbitLabel, precisionLabel, recordedDateLabel, roleLabel, timeSinceLaunch,
} from '../separations/separation-format';

/** Three days, as on a satellite's page. */
export const SEPARATION_WINDOW_HOURS = 72;

/** Why the page shows no passes, when it shows none. */
type Sighting =
  | 'passes'          // a prediction is shown (or loading)
  | 'several'         // many objects: each has its own page
  | 'gone'            // re-entered, landed or deorbited
  | 'no-number'       // no catalogue number at all
  | 'not-yet'         // a catalogue number the predictor does not accept yet
  | 'not-public';     // no published elements: positions withheld

/**
 * One separation: what released what, when (with the precision the record has), the
 * lineage, both orbits, the record itself as evidence, and when the released object
 * passes over the reader - or, as plainly, why that cannot be said.
 */
@Component({
  selector: 'app-separation',
  imports: [DatePipe, DecimalPipe, RouterLink, Reveal, NextPass, PassTable],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './separation.html',
  styleUrl: './separation.scss',
})
export class SeparationPage {
  private readonly router = inject(Router);
  private readonly seo = inject(Seo);
  private readonly locale = inject(LOCALE_ID);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));

  /** The route parameter: any record of the event. */
  readonly id = input.required<string>();

  protected readonly windowHours = SEPARATION_WINDOW_HOURS;

  protected readonly resource = httpResource<SeparationEvent>(() =>
    this.browser ? `/api/separations/${encodeURIComponent(this.id())}` : undefined);
  protected readonly event = computed(() => (this.resource.hasValue() ? this.resource.value() : undefined));

  /** The released object, when there is one; a breakup's fragments are listed instead. */
  protected readonly subject = computed<SpaceObject | undefined>(() => {
    const event = this.event();
    return event?.childCount === 1 ? event.children[0] : undefined;
  });

  protected readonly parentName = computed(() => {
    const event = this.event();
    return event?.parent?.name ?? event?.children[0]?.evidence.parent ?? '';
  });

  protected readonly heading = computed(() => {
    const event = this.event();
    if (!event) return $localize`:Heading while an event loads:Separation`;
    const parent = this.parentName();
    if (event.kind === 'FRAGMENTATION') {
      return event.childCount === 1
        ? $localize`:Heading of a one-fragment event:A fragment separated from ${parent}:parent:`
        : $localize`:Heading of a fragmentation:${event.childCount}:count: fragments separated from ${parent}:parent:`;
    }
    const subject = this.subject();
    return subject
      ? $localize`:Heading of a release:${parent}:parent: released ${subject.name ?? subject.id}:child:`
      : $localize`:Heading of a release of several objects:${parent}:parent: released ${event.childCount}:count: objects`;
  });

  protected readonly when = computed(() => {
    const event = this.event();
    return event ? { label: recordedDateLabel(event.date, this.locale), precision: precisionLabel(event.date) } : undefined;
  });
  /** One factual sentence under the heading: when, and the recorded payload name if it says more. */
  protected readonly lede = computed(() => {
    const event = this.event();
    const when = this.when();
    if (!event || !when) return undefined;
    const subject = this.subject();
    const date = $localize`:Lede of a separation, the date:Recorded as ${when.label}:date:, ${when.precision}:precision:.`;
    const payload = subject?.payloadName && subject.payloadName !== subject.name
      ? ' ' + $localize`:Lede of a separation, the payload name:Its recorded payload name: ${subject.payloadName}:payload:.`
      : '';
    return date + payload;
  });

  protected readonly afterLaunch = computed(() => {
    const event = this.event();
    return event ? timeSinceLaunch(event.children[0]?.launch ?? null, event.date) : undefined;
  });
  protected readonly geostationary = computed(() => {
    const first = this.event()?.children[0];
    return first ? isGeostationary(first.orbit) : false;
  });

  protected readonly orbit = orbitLabel;

  /** A fragment's own page exists only for a number the predictor accepts, and an object still in orbit. */
  protected linkable(child: SpaceObject): boolean {
    return child.inOrbit && child.noradId !== null && asNoradId(String(child.noradId)) !== undefined;
  }
  protected readonly role = roleLabel;

  protected readonly sighting = computed<Sighting>(() => {
    const event = this.event();
    const subject = this.subject();
    if (!event) return 'passes';
    if (!subject) return 'several';
    if (!subject.inOrbit) return 'gone';
    if (subject.noradId === null) return 'no-number';
    if (asNoradId(String(subject.noradId)) === undefined) return 'not-yet';
    if (this.passesProblem()?.type.endsWith('/unknown-satellite')) return 'not-public';
    return 'passes';
  });

  protected readonly observer = signal({ lat: DEFAULT_QUERY.lat, lon: DEFAULT_QUERY.lon, alt: DEFAULT_QUERY.alt });
  protected readonly isDefaultObserver = computed(() =>
    this.observer().lat === DEFAULT_QUERY.lat && this.observer().lon === DEFAULT_QUERY.lon);

  /** The subject's noradId when a prediction can be asked for, otherwise nothing is fetched. */
  private readonly predictable = computed(() => {
    const subject = this.subject();
    if (!subject?.inOrbit || subject.noradId === null) return undefined;
    return asNoradId(String(subject.noradId));
  });

  protected readonly passes = httpResource<PassesResponse>(() => {
    const noradId = this.predictable();
    if (noradId === undefined) return undefined;
    const { lat, lon, alt } = this.observer();
    return {
      url: '/api/passes',
      params: { noradId, lat, lon, alt, hours: SEPARATION_WINDOW_HOURS, minElevation: DEFAULT_QUERY.minElevation },
    };
  });
  protected readonly prediction = computed(() => (this.passes.hasValue() ? this.passes.value() : undefined));
  protected readonly visibleCount = computed(() =>
    (this.prediction()?.passes ?? []).filter((pass) => visibleWindow(pass) !== undefined).length);

  protected readonly problem = computed(() => problemOf(this.resource.error()));
  protected readonly passesProblem = computed(() => problemOf(this.passes.error()));
  protected readonly notFound = computed(() => this.problem()?.status === 404);

  protected readonly locating = signal(false);
  protected readonly locationError = signal<string | undefined>(undefined);

  constructor() {
    // The route can only name the page; the heading arrives with the event.
    effect(() => {
      const event = this.event();
      if (event) this.seo.apply(this.router.url, { title: this.heading(), description: this.description(event) });
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

  /** The pass, opened in the predictor with its globe and sky chart. */
  protected inspect(instant: string): void {
    const noradId = this.predictable();
    if (noradId === undefined) return;
    const { lat, lon, alt } = this.observer();
    void this.router.navigate(['/'], {
      queryParams: { norad: noradId, lat, lon, alt, hours: SEPARATION_WINDOW_HOURS, minEl: DEFAULT_QUERY.minElevation, pass: instant },
    });
  }

  protected reload(): void {
    if (this.resource.error()) this.resource.reload();
    else this.passes.reload();
  }

  private description(event: SeparationEvent): string {
    const when = recordedDateLabel(event.date, this.locale);
    return $localize`:Meta description of a separation:${this.heading()}:heading: (${when}:date:): the record, the lineage, both orbits and when to see it pass over you.`;
  }
}

function problemOf(error: unknown): ProblemDetail | undefined {
  if (!error) return undefined;
  const body = error instanceof HttpErrorResponse ? error.error : undefined;
  if (body && typeof body === 'object' && 'title' in body) return body as ProblemDetail;
  return { type: 'about:blank', title: $localize`The backend could not be reached`, status: 0, detail: $localize`The server may be starting. Please try again shortly.` };
}
