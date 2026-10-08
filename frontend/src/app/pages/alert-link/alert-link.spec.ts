import { describe, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { AlertLinkPage } from './alert-link';

const TOKEN = 'a'.repeat(43);

function render(action: 'confirm' | 'unsubscribe', token: string | null = TOKEN) {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: {
        data: { action }, queryParamMap: convertToParamMap(token === null ? {} : { token }),
      } } },
    ],
  });
  const fixture = TestBed.createComponent(AlertLinkPage);
  TestBed.tick();
  return fixture.nativeElement as HTMLElement;
}

function click(page: HTMLElement) {
  (page.querySelector('button.btn.primary') as HTMLButtonElement).click();
  TestBed.tick();
}

describe('AlertLinkPage', () => {
  it('confirms only on a click, never on load, and says what was turned on', () => {
    const page = render('confirm');
    const http = TestBed.inject(HttpTestingController);
    http.expectNone(() => true);

    click(page);
    const request = http.expectOne((r) => r.url === '/api/alerts/confirm');
    expect(request.request.method).toBe('POST');
    expect(request.request.params.get('token')).toBe(TOKEN);
    request.flush({ noradId: 25544, lat: 45.76, lon: 4.84, minElevationDeg: 30, maxCloudPercent: 25 });
    TestBed.tick();

    expect(page.textContent).toContain('NORAD 25544');
    expect(page.textContent).toContain('at least 30°');
  });

  it('says an expired link is expired', () => {
    const page = render('confirm');
    click(page);
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/alerts/confirm')
      .flush({ title: 'Unknown or expired link', status: 404 }, { status: 404, statusText: 'Not Found' });
    TestBed.tick();

    expect(page.textContent).toContain('unknown or has expired');
  });

  it('unsubscribes on a click', () => {
    const page = render('unsubscribe');
    click(page);
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/alerts/unsubscribe')
      .flush(null, { status: 204, statusText: 'No Content' });
    TestBed.tick();

    expect(page.textContent).toContain('This reminder is deleted');
  });

  it('offers nothing to click without a token', () => {
    const page = render('unsubscribe', null);

    expect(page.querySelector('button.btn.primary')).toBeNull();
    expect(page.textContent).toContain('unknown or has expired');
  });
});
