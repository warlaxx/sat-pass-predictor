import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { PassesApi } from './api/passes.service';
import { PassQuery, DEFAULT_QUERY, MAX_WINDOW_HOURS } from './api/passes.query';
import { ProblemDetail } from './api/passes.model';
import { TleBanner } from './tle-banner/tle-banner';
import { PassTable } from './pass-table/pass-table';

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
  imports: [TleBanner, PassTable],
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
  protected readonly response = computed(() => this.resource.value());
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
      detail: 'Check that the API is running on port 8080.',
    };
  });

  protected readonly isEmptyResult = computed(() => {
    const response = this.response();
    return response !== undefined && response.passes.length === 0;
  });

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
