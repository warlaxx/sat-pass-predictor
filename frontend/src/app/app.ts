import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { PassesApi } from './api/passes.service';
import { PassQuery, DEFAULT_QUERY, MAX_WINDOW_HOURS } from './api/passes.query';
import { ProblemDetail } from './api/passes.model';
import { TleBanner } from './tle-banner/tle-banner';
import { PassRibbon } from './pass-ribbon/pass-ribbon';
import { PassViewer } from './pass-viewer/pass-viewer';
import { PassTable } from './pass-table/pass-table';

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
  imports: [TleBanner, PassRibbon, PassTable, PassViewer],
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
  protected readonly response = computed(() => this.resource.hasValue() ? this.resource.value() : undefined);
  protected readonly selectedPass = computed(() => {
    const passes = this.response()?.passes ?? [];
    return passes.find(pass => pass.aos.instant === this.selected()) ?? passes[0];
  });
  protected readonly searched = computed(() => this.api.lastQuery() !== undefined);

  /**
   * The error, as the API means it to be read.
   *
   * The body of a failure is a Problem Detail, so `type` is what distinguishes an
   * unknown satellite from a CelesTrak outage - two answers that share status 503 have
   * nothing else to tell them apart. A transport failure has no body at all, hence the
   * fallback sentence.
   */
  protected readonly problem = computed<ProblemDetail | undefined>(() => {
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

  protected search(): void {
    this.selected.set(undefined);
    this.api.search(this.form());
  }

  protected reload(): void {
    this.api.reload();
  }
}
