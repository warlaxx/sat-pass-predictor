import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import { Reveal } from '../../motion/reveal';
import { formatAge } from '../../format';

export type ProbeState = 'checking' | 'ok' | 'slow' | 'down';

export interface ProbeResult {
  readonly state: ProbeState;
  readonly millis?: number;
  readonly detail: string;
}

/** Above this, an answer is a cold start rather than a healthy instance. */
export const SLOW_MS = 5_000;
/** The satellite catalogue is refreshed daily; beyond two days something is wrong. */
export const CATALOGUE_STALE_S = 2 * 86_400;

/**
 * The live state of the service, as this browser measures it right now.
 *
 * Two probes, both free for the quota: the backend's actuator health (the same path Render
 * checks), and the name search, which also says how old the satellite catalogue is.
 * Neither calls /api/passes - a status page read a hundred times a day must not spend the
 * anonymous budget the predictor lives on.
 *
 * This is a snapshot, not an uptime history. There is no external monitor yet, and the
 * page says so rather than drawing ninety green bars it could not back.
 */
@Component({
  selector: 'app-status',
  imports: [DatePipe, RouterLink, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './status.html',
  styles: `
    .probes { display: flex; flex-direction: column; }
    .probe {
      align-items: center;
      border-bottom: 1px solid var(--line-soft);
      display: grid;
      gap: 6px 18px;
      grid-template-columns: auto 1fr auto;
      padding: 20px 24px;
    }
    .probe:last-child { border-bottom: 0; }
    .probe h3 { font-size: 20px; }
    .probe p { color: var(--ink-2); grid-column: 2 / -1; margin: 0; }
    .probe .num { color: var(--ink-3); font-size: 13px; text-align: right; }
    .overall { align-items: center; display: flex; gap: 14px; font-size: 18px; }
  `,
})
export class StatusPage {
  private readonly http = inject(HttpClient);

  protected readonly backend = signal<ProbeResult>({ state: 'checking', detail: 'Asking the backend…' });
  protected readonly catalogue = signal<ProbeResult>({ state: 'checking', detail: 'Searching the catalogue…' });
  protected readonly checkedAt = signal<Date | undefined>(undefined);

  protected readonly overall = computed<ProbeState>(() => {
    const states = [this.backend().state, this.catalogue().state];
    if (states.includes('checking')) return 'checking';
    if (states.includes('down')) return 'down';
    return states.includes('slow') ? 'slow' : 'ok';
  });

  protected readonly probes = computed(() => [
    { name: 'Prediction backend', result: this.backend() },
    { name: 'Satellite catalogue', result: this.catalogue() },
  ]);

  protected readonly checking = computed(() => this.overall() === 'checking');

  constructor() {
    void this.check();
  }

  protected async check(): Promise<void> {
    this.backend.set({ state: 'checking', detail: 'Asking the backend…' });
    this.catalogue.set({ state: 'checking', detail: 'Searching the catalogue…' });
    // One after the other: the first wakes a sleeping instance, so the second measures
    // the catalogue rather than the same cold start twice.
    this.backend.set(await this.probeBackend());
    this.catalogue.set(await this.probeCatalogue());
    this.checkedAt.set(new Date());
  }

  private async probeBackend(): Promise<ProbeResult> {
    const start = performance.now();
    try {
      const body = await firstValueFrom(
        this.http.get<{ status?: string }>('/status-probe/health').pipe(timeout(60_000)),
      );
      const millis = Math.round(performance.now() - start);
      if (body?.status !== 'UP') {
        return { state: 'down', millis, detail: `The backend answered, but reports itself ${body?.status ?? 'unknown'}.` };
      }
      return millis > SLOW_MS
        ? { state: 'slow', millis, detail: 'Up, after a cold start: the instance was asleep. Calls are fast again now.' }
        : { state: 'ok', millis, detail: 'Up and answering.' };
    } catch {
      return { state: 'down', millis: Math.round(performance.now() - start), detail: 'No answer from the backend within 60 s.' };
    }
  }

  private async probeCatalogue(): Promise<ProbeResult> {
    const start = performance.now();
    try {
      const body = await firstValueFrom(
        this.http.get<{ results: unknown[]; catalogFetchedAt: string }>('/api/satellites', { params: { q: 'ISS', limit: 1 } })
          .pipe(timeout(60_000)),
      );
      const millis = Math.round(performance.now() - start);
      const ageSeconds = (Date.now() - Date.parse(body.catalogFetchedAt)) / 1000;
      if (!Number.isFinite(ageSeconds)) return { state: 'slow', millis, detail: 'Searchable, but the catalogue does not say when it was fetched.' };
      const age = ageSeconds < 3600 ? 'less than an hour' : formatAge(ageSeconds);
      return ageSeconds > CATALOGUE_STALE_S
        ? { state: 'slow', millis, detail: `Searchable, but the catalogue was fetched ${age} ago: new launches may be missing.` }
        : { state: 'ok', millis, detail: `Searchable. Catalogue fetched ${age} ago from CelesTrak.` };
    } catch {
      return {
        state: 'down', millis: Math.round(performance.now() - start),
        detail: 'Name search unavailable. NORAD numbers still work in the predictor.',
      };
    }
  }
}
