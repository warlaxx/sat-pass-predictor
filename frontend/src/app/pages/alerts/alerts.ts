import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PassesResponse, ProblemDetail } from '../../api/passes.model';
import { DEFAULT_QUERY } from '../../api/passes.query';
import { asNoradId } from '../../api/satellites.service';
import { saveCalendar, visibleWindow } from '../../calendar/ics';
import { compassPoint } from '../../format';
import { Reveal } from '../../motion/reveal';
import { SatellitePicker } from '../../satellite-picker/satellite-picker';
import { requestPosition } from '../../shared/geolocation';

/** One request covers the whole window; ten days is the backend's own bound. */
export const MAX_DAYS = 10;

interface AlertQuery {
  readonly noradId: number;
  readonly lat: number;
  readonly lon: number;
  readonly alt: number;
  readonly days: number;
  readonly minElevation: number;
}

/**
 * Reminders before the passes worth going outside for.
 *
 * Today that means a calendar file: one event per potentially visible pass, each with a
 * reminder ten minutes before the satellite comes into view. It needs no account, no
 * server-side schedule and no permission to notify, and it keeps working with the tab
 * closed - which a browser notification would not. Webhooks for integrations are the
 * server-side version, and the page says plainly that they do not exist yet.
 */
@Component({
  selector: 'app-alerts',
  imports: [DatePipe, DecimalPipe, RouterLink, Reveal, SatellitePicker],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './alerts.html',
  styles: `
    .windows { display: flex; flex-direction: column; }
    .window {
      align-items: baseline;
      border-bottom: 1px solid var(--line-soft);
      display: grid;
      gap: 4px 24px;
      grid-template-columns: minmax(150px, max-content) 1fr auto;
      padding: 16px 20px;
    }
    .window:last-child { border-bottom: 0; }
    .window .when { color: var(--ink); font-size: 17px; }
    .window .how { color: var(--ink-2); }
    .window .peak { color: var(--ink-3); font-size: 13px; }
    @media (width < 640px) { .window { grid-template-columns: 1fr; } }
  `,
})
export class AlertsPage {
  protected readonly maxDays = MAX_DAYS;
  protected readonly form = signal<AlertQuery>({
    noradId: asNoradId(inject(ActivatedRoute).snapshot.queryParamMap.get('norad') ?? '') ?? DEFAULT_QUERY.noradId,
    lat: DEFAULT_QUERY.lat,
    lon: DEFAULT_QUERY.lon,
    alt: DEFAULT_QUERY.alt,
    days: 7,
    minElevation: DEFAULT_QUERY.minElevation,
  });
  protected readonly satelliteResolved = signal(true);
  protected readonly validation = signal<string | undefined>(undefined);
  private readonly query = signal<AlertQuery | undefined>(undefined);

  protected readonly resource = httpResource<PassesResponse>(() => {
    const query = this.query();
    if (!query) return undefined;
    const { noradId, lat, lon, alt, days, minElevation } = query;
    return { url: '/api/passes', params: { noradId, lat, lon, alt, hours: days * 24, minElevation } };
  });

  protected readonly response = computed(() => (this.resource.hasValue() ? this.resource.value() : undefined));

  protected readonly problem = computed<ProblemDetail | undefined>(() => {
    const error = this.resource.error();
    if (!error) return undefined;
    const body = error instanceof HttpErrorResponse ? error.error : undefined;
    if (body && typeof body === 'object' && 'title' in body) return body as ProblemDetail;
    return { type: 'about:blank', title: 'The backend could not be reached', status: 0, detail: 'The server may be starting. Please try again shortly.' };
  });

  /** The favourable interval of every pass that has one: what the calendar will hold. */
  protected readonly windows = computed(() => (this.response()?.passes ?? []).flatMap((pass) => {
    const window = visibleWindow(pass);
    return window ? [{ ...window, pass, direction: compassPoint(pass.culmination.azimuthDeg) }] : [];
  }));

  protected readonly locating = signal(false);
  protected readonly locationError = signal<string | undefined>(undefined);

  protected setSatellite(noradId: number | null): void {
    this.satelliteResolved.set(noradId !== null);
    if (noradId !== null) this.form.update((form) => ({ ...form, noradId }));
  }

  protected patch(field: keyof AlertQuery, value: string): void {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) this.form.update((form) => ({ ...form, [field]: parsed }));
  }

  protected useMyPosition(): void {
    this.locationError.set(undefined);
    this.locating.set(true);
    requestPosition().then(
      (position) => this.form.update((form) => ({ ...form, lat: position.lat, lon: position.lon, alt: position.alt ?? form.alt })),
      (error: Error) => this.locationError.set(error.message),
    ).finally(() => this.locating.set(false));
  }

  protected search(): void {
    this.validation.set(undefined);
    const form = this.form();
    if (!this.satelliteResolved()) {
      this.validation.set('Choose a satellite from the suggestions, or type its NORAD number.');
      return;
    }
    const bounds: [number, number, number][] = [
      [form.lat, -90, 90], [form.lon, -180, 180], [form.alt, -500, 9000], [form.days, 1, MAX_DAYS], [form.minElevation, 0, 89],
    ];
    if (bounds.some(([value, min, max]) => !Number.isFinite(value) || value < min || value > max)) {
      this.validation.set(`Check the fields: latitude −90 to 90, longitude −180 to 180, 1 to ${MAX_DAYS} days, threshold 0 to 89°.`);
      return;
    }
    this.query.set({ ...form, days: Math.round(form.days) });
  }

  protected download(): void {
    const response = this.response();
    if (response) saveCalendar(response);
  }
}
