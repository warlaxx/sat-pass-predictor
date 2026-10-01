import { ChangeDetectionStrategy, Component, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { DecimalPipe, isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { PassesResponse, ProblemDetail } from '../../api/passes.model';
import { DEFAULT_QUERY } from '../../api/passes.query';
import { LaunchesResponse } from '../../api/satellites.service';
import { visibleWindow } from '../../calendar/ics';
import { Reveal } from '../../motion/reveal';
import { NextPass } from '../../next-pass/next-pass';
import { PassTable } from '../../pass-table/pass-table';
import { requestPosition } from '../../shared/geolocation';

/** Three days, as on a satellite's page: a fresh train changes faster than that. */
export const STARLINK_WINDOW_HOURS = 72;

/** The newest launches offered: the train is the newest one, the others for comparison. */
export const STARLINK_LAUNCHES = 3;

/**
 * "What was that line of lights?": the newest Starlink launches and when their train
 * passes over the reader.
 *
 * The text is prerendered, for the people who search for it; the launches and the
 * passes are the browser's, like every pass on the site. A train is a group flying close
 * together, so the passes of its lowest-numbered satellite stand for the group's - the
 * others follow within seconds to minutes while the train is still compact.
 */
@Component({
  selector: 'app-starlink',
  imports: [DecimalPipe, RouterLink, Reveal, NextPass, PassTable],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './starlink.html',
  styles: `
    .launches { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
    .launches .btn[aria-pressed='true'] { border-color: var(--signal); color: var(--ink); }
    .observer { align-items: center; display: flex; flex-wrap: wrap; gap: 12px 20px; }
    .split { display: grid; gap: 32px 64px; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); }
  `,
})
export class StarlinkPage {
  private readonly router = inject(Router);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));

  protected readonly windowHours = STARLINK_WINDOW_HOURS;

  protected readonly launches = httpResource<LaunchesResponse>(() =>
    this.browser ? { url: '/api/satellites/launches', params: { q: 'starlink', limit: STARLINK_LAUNCHES } } : undefined);

  /** The launch whose passes are shown: the reader's choice, the newest otherwise. */
  private readonly chosen = signal<string | undefined>(undefined);
  protected readonly launchList = computed(() => (this.launches.hasValue() ? this.launches.value().launches : []));
  protected readonly launch = computed(() => {
    const list = this.launchList();
    return list.find((launch) => launch.designator === this.chosen()) ?? list[0];
  });

  protected readonly observer = signal({ lat: DEFAULT_QUERY.lat, lon: DEFAULT_QUERY.lon, alt: DEFAULT_QUERY.alt });
  protected readonly isDefaultObserver = computed(() =>
    this.observer().lat === DEFAULT_QUERY.lat && this.observer().lon === DEFAULT_QUERY.lon);

  protected readonly passes = httpResource<PassesResponse>(() => {
    const launch = this.launch();
    if (!launch) return undefined;
    const { lat, lon, alt } = this.observer();
    return {
      url: '/api/passes',
      params: { noradId: launch.noradId, lat, lon, alt, hours: STARLINK_WINDOW_HOURS, minElevation: DEFAULT_QUERY.minElevation },
    };
  });
  protected readonly response = computed(() => (this.passes.hasValue() ? this.passes.value() : undefined));
  protected readonly visibleCount = computed(() =>
    (this.response()?.passes ?? []).filter((pass) => visibleWindow(pass) !== undefined).length);

  /** The first failure of the two requests, as the backend described it. */
  protected readonly problem = computed<ProblemDetail | undefined>(() => {
    const error = this.launches.error() ?? this.passes.error();
    if (!error) return undefined;
    const body = error instanceof HttpErrorResponse ? error.error : undefined;
    if (body && typeof body === 'object' && 'title' in body) return body as ProblemDetail;
    return { type: 'about:blank', title: $localize`The backend could not be reached`, status: 0, detail: $localize`The server may be starting. Please try again shortly.` };
  });

  protected readonly locating = signal(false);
  protected readonly locationError = signal<string | undefined>(undefined);

  protected choose(designator: string): void {
    this.chosen.set(designator);
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
    const launch = this.launch();
    if (!launch) return;
    const { lat, lon, alt } = this.observer();
    void this.router.navigate(['/'], {
      queryParams: { norad: launch.noradId, lat, lon, alt, hours: STARLINK_WINDOW_HOURS, minEl: DEFAULT_QUERY.minElevation, pass: instant },
    });
  }

  protected reload(): void {
    if (this.launches.error()) this.launches.reload();
    else this.passes.reload();
  }
}
