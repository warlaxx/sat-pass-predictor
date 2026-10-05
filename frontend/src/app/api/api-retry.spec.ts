import { PLATFORM_ID, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ATTEMPT_TIMEOUT_MS, ApiWaiting, RETRY_DELAYS_MS, SLOW_AFTER_MS, apiRetryInterceptor, isTransient } from './api-retry';

const PROBLEM = { type: 'https://nextpass.space/problems/tle-unavailable', title: 'Orbital elements unavailable', status: 503 };

function setUp(platform = 'browser') {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: PLATFORM_ID, useValue: platform },
      provideHttpClient(withInterceptors([apiRetryInterceptor])),
      provideHttpClientTesting(),
    ],
  });
  return { http: TestBed.inject(HttpClient), backend: TestBed.inject(HttpTestingController), waiting: TestBed.inject(ApiWaiting) };
}

/** Subscribes, and keeps what came back. */
function get(http: HttpClient, url = '/api/separations') {
  const outcome: { value?: unknown; error?: HttpErrorResponse } = {};
  http.get(url).subscribe({ next: (value) => (outcome.value = value), error: (error) => (outcome.error = error) });
  return outcome;
}

describe('apiRetryInterceptor', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('tries again after a 502 and hands the page the 200 that follows', () => {
    const { http, backend } = setUp();
    const outcome = get(http);

    backend.expectOne('/api/separations').flush('<html>Bad gateway</html>', { status: 502, statusText: 'Bad Gateway' });
    expect(outcome.error).toBeUndefined();
    backend.expectNone('/api/separations');

    vi.advanceTimersByTime(RETRY_DELAYS_MS[0]);
    backend.expectOne('/api/separations').flush({ events: [] });

    expect(outcome.value).toEqual({ events: [] });
    expect(outcome.error).toBeUndefined();
    backend.verify();
  });

  it('waits longer before each new attempt, and gives up after three', () => {
    const { http, backend } = setUp();
    const outcome = get(http);

    backend.expectOne('/api/separations').error(new ProgressEvent('error'));
    for (const delay of RETRY_DELAYS_MS) {
      vi.advanceTimersByTime(delay - 1);
      backend.expectNone('/api/separations');
      vi.advanceTimersByTime(1);
      backend.expectOne('/api/separations').flush(null, { status: 504, statusText: 'Gateway Timeout' });
    }

    expect(outcome.error?.status).toBe(504);
    vi.advanceTimersByTime(60_000);
    backend.verify();
  });

  it('does not repeat an answer the backend gave on purpose', () => {
    const { http, backend } = setUp();
    const outcome = get(http, '/api/passes?norad=25544');

    backend.expectOne('/api/passes?norad=25544').flush(PROBLEM, { status: 503, statusText: 'Service Unavailable' });

    expect(outcome.error?.error).toEqual(PROBLEM);
    vi.advanceTimersByTime(10_000);
    backend.verify();
  });

  it('drops a silent connection and opens a new one', () => {
    const { http, backend } = setUp();
    const outcome = get(http);

    const stuck = backend.expectOne('/api/separations');
    vi.advanceTimersByTime(ATTEMPT_TIMEOUT_MS);
    expect(stuck.cancelled).toBe(true);

    vi.advanceTimersByTime(RETRY_DELAYS_MS[0]);
    backend.expectOne('/api/separations').flush({ events: [] });
    expect(outcome.value).toEqual({ events: [] });
  });

  it('leaves writes, other hosts and the prerender alone', () => {
    const { http, backend } = setUp();
    http.post('/api/usage/list-open-event', null).subscribe({ error: () => undefined });
    http.get('/status-probe/health').subscribe({ error: () => undefined });
    backend.expectOne('/api/usage/list-open-event').flush(null, { status: 502, statusText: 'Bad Gateway' });
    backend.expectOne('/status-probe/health').flush(null, { status: 502, statusText: 'Bad Gateway' });
    vi.advanceTimersByTime(10_000);
    backend.verify();
  });

  it('does nothing on the server', () => {
    const { http, backend } = setUp('server');
    get(http);
    backend.expectOne('/api/separations').flush(null, { status: 502, statusText: 'Bad Gateway' });
    vi.advanceTimersByTime(10_000);
    backend.verify();
  });

  it('says the server is slow after five seconds, and stops saying it once answered', () => {
    const { http, backend, waiting } = setUp();
    get(http);

    vi.advanceTimersByTime(SLOW_AFTER_MS - 1);
    expect(waiting.slow()).toBe(false);
    vi.advanceTimersByTime(1);
    expect(waiting.slow()).toBe(true);

    backend.expectOne('/api/separations').flush({ events: [] });
    expect(waiting.slow()).toBe(false);
  });
});

describe('isTransient', () => {
  const failure = (status: number, error: unknown = null) => new HttpErrorResponse({ status, error });

  it('retries the network and the proxy, not the backend or the visitor', () => {
    expect(isTransient(failure(0))).toBe(true);
    expect(isTransient(failure(502, '<html></html>'))).toBe(true);
    expect(isTransient(failure(503))).toBe(true);
    expect(isTransient(failure(504))).toBe(true);
    expect(isTransient(failure(503, PROBLEM))).toBe(false);
    expect(isTransient(failure(500))).toBe(false);
    expect(isTransient(failure(429))).toBe(false);
    expect(isTransient(failure(404))).toBe(false);
    expect(isTransient(new Error('boom'))).toBe(false);
  });
});
