import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, InjectionToken, LOCALE_ID, TransferState, afterNextRender, computed,
  inject, makeStateKey, signal,
} from '@angular/core';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { SeparationSummary, SeparationsResponse } from '../api/separations.model';
import { eventRow } from '../pages/separations/separation-format';
import { countUsage } from '../shared/usage';

/** How many events the home page shows. */
export const HOME_SEPARATIONS = 5;

/** The newest events of the build's snapshot; provided on the server only (app.config.server.ts). */
export const PRERENDERED_SEPARATIONS = new InjectionToken<readonly SeparationSummary[]>('PRERENDERED_SEPARATIONS');

const PRERENDERED = makeStateKey<readonly SeparationSummary[]>('latest-separations');

/**
 * The events the prerendered HTML shows. The server writes them into the transfer state,
 * so that the browser hydrates the same list rather than a loading line, and replaces it
 * once the API has answered.
 */
function prerendered(): readonly SeparationSummary[] {
  const state = inject(TransferState);
  const snapshot = inject(PRERENDERED_SEPARATIONS, { optional: true });
  if (snapshot) state.set(PRERENDERED, snapshot);
  return state.get(PRERENDERED, []);
}

/**
 * What just separated in orbit, on the home page: the part of the site other predictors
 * do not have, a click from each event's page. Written at build time, so the text is
 * indexed; refreshed by the API in the browser, since a build can be days old.
 *
 * The refresh waits until the block comes near the screen: the home page asks the API
 * nothing the reader has not come to, and most readers stop at the predictor.
 */
@Component({
  selector: 'app-latest-separations',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './latest-separations.html',
  styleUrl: './latest-separations.scss',
})
export class LatestSeparations {
  private readonly locale = inject(LOCALE_ID);
  private readonly prerendered = prerendered();
  private readonly near = signal(false);

  protected readonly resource = httpResource<SeparationsResponse>(() =>
    this.near() ? { url: '/api/separations', params: { limit: HOME_SEPARATIONS } } : undefined);

  protected readonly rows = computed(() =>
    (this.resource.hasValue() ? this.resource.value().events : this.prerendered)
      .slice(0, HOME_SEPARATIONS)
      .map((event) => eventRow(event, this.locale)));

  constructor() {
    const host = inject(ElementRef).nativeElement as HTMLElement;
    const destroyRef = inject(DestroyRef);
    // Browser only. Without an observer, the prerendered list simply stays.
    afterNextRender(() => {
      if (typeof IntersectionObserver === 'undefined') return;
      const observer = new IntersectionObserver((entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        this.near.set(true);
        observer.disconnect();
      }, { rootMargin: '600px 0px' });
      observer.observe(host);
      destroyRef.onDestroy(() => observer.disconnect());
    });
  }

  protected countOpen(): void {
    countUsage('home-open-event');
  }
}
