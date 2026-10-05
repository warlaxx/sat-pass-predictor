import { Injectable, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { finalize, retry, throwError, timeout, timer } from 'rxjs';

/** Past this, a request that has not answered is a sleeping server, and the page says so. */
export const SLOW_AFTER_MS = 5_000;

/**
 * The longest one attempt may stay silent. A cold start answers within about 30 s; a
 * connection still silent after this one is stuck, and a fresh one does better.
 */
export const ATTEMPT_TIMEOUT_MS = 25_000;

/** The pause before each new attempt: three of them, longer each time. */
export const RETRY_DELAYS_MS = [1_000, 2_000, 4_000] as const;

const TRANSIENT_STATUSES = new Set([0, 502, 503, 504]);

/**
 * True for a failure that a second attempt may cure: the network, a timeout, or the
 * host's proxy answering 502, 503 or 504 while the instance boots.
 *
 * A Problem Detail is the backend itself speaking. Its 503 - no orbital elements, no
 * catalogue - means the same thing a second later, and on /api/passes each attempt
 * would spend the visitor's quota for nothing.
 */
export function isTransient(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse) || !TRANSIENT_STATUSES.has(error.status)) return false;
  const body: unknown = error.error;
  return !(body && typeof body === 'object' && 'title' in body);
}

/**
 * Whether a request is still waiting on the API after {@link SLOW_AFTER_MS}. The shell
 * reads it to tell the visitor the server is waking up, whichever page asked.
 */
@Injectable({ providedIn: 'root' })
export class ApiWaiting {
  private readonly late = signal(0);
  readonly slow = computed(() => this.late() > 0);

  /** Starts the clock of one request; the returned function stops it. */
  start(): () => void {
    let late = false;
    const clock = setTimeout(() => {
      late = true;
      this.late.update((count) => count + 1);
    }, SLOW_AFTER_MS);
    return () => {
      clearTimeout(clock);
      if (late) this.late.update((count) => count - 1);
    };
  }
}

/**
 * Every GET to the API, in the browser: a silent connection is dropped after
 * {@link ATTEMPT_TIMEOUT_MS}, and a transient failure is tried again up to three times.
 * Only the last failure reaches the page, which then shows its message and its
 * "Try again" button.
 *
 * Not on the server: the prerender at build time must not wait on a sleeping API.
 * Not for writes either, which are not ours to repeat.
 */
export const apiRetryInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isPlatformBrowser(inject(PLATFORM_ID)) || request.method !== 'GET' || !isApi(request)) return next(request);
  const stop = inject(ApiWaiting).start();
  return next(request).pipe(
    timeout({ each: ATTEMPT_TIMEOUT_MS, with: () => throwError(() => timedOut(request)) }),
    retry({
      count: RETRY_DELAYS_MS.length,
      delay: (error, attempt) => (isTransient(error) ? timer(RETRY_DELAYS_MS[attempt - 1]) : throwError(() => error)),
    }),
    finalize(stop),
  );
};

function isApi(request: HttpRequest<unknown>): boolean {
  return new URL(request.url, 'http://localhost').pathname.startsWith('/api/');
}

/** A timeout, shaped like the network failure it is to the pages: status 0, no body. */
function timedOut(request: HttpRequest<unknown>): HttpErrorResponse {
  return new HttpErrorResponse({ status: 0, statusText: 'Timeout', url: request.urlWithParams });
}
