import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { SeparationSummary, SeparationsResponse } from '../api/separations.model';
import { HOME_SEPARATIONS, LatestSeparations, PRERENDERED_SEPARATIONS } from './latest-separations';

/** Real events of the record, as the backend listed them on 2 October 2026. */
const USA_667: SeparationSummary = {
  id: 'S100685', kind: 'RELEASE', date: { text: '2026 Sep?', at: '2026-09-01T00:00:00Z', precision: 'MONTH', uncertain: true },
  parentName: 'USA 396', parentOwner: 'AFRL', parentState: 'US', firstChildName: 'USA 667', firstChildNoradId: 100685,
  children: 1, orbit: { perigeeKm: 35800, apogeeKm: 36100, inclinationDeg: 0, orbitClass: 'GEO/D' }, inOrbit: true,
};
const YAOGAN: SeparationSummary = {
  id: 'S100564', kind: 'FRAGMENTATION', date: { text: '2026 Jul 20?', at: '2026-07-20T00:00:00Z', precision: 'DAY', uncertain: true },
  parentName: 'Yaogan 50 hao 02 xing', parentOwner: 'ZXW', parentState: 'CN', firstChildName: 'deb YG50-02', firstChildNoradId: 100564,
  children: 62, orbit: { perigeeKm: 593, apogeeKm: 1085, inclinationDeg: 97, orbitClass: 'LEO/R' }, inOrbit: true,
};

function list(events: SeparationSummary[]): SeparationsResponse {
  return { events, stats: { objects: 28364, objectsThisYear: 105, year: 2026, byMonth: [] }, updatedAt: '2026-10-02T16:42:00Z' };
}

/** An observer that sees its element on screen at once, as when the reader scrolls to it. */
class OnScreen {
  constructor(private readonly callback: (entries: { isIntersecting: boolean }[]) => void) {}
  observe(): void { queueMicrotask(() => this.callback([{ isIntersecting: true }])); }
  disconnect(): void {}
}

describe('LatestSeparations', () => {
  beforeEach(() => vi.stubGlobal('IntersectionObserver', OnScreen));
  afterEach(() => vi.unstubAllGlobals());

  async function mount(prerendered?: SeparationSummary[]) {
    TestBed.configureTestingModule({
      imports: [LatestSeparations],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        ...(prerendered ? [{ provide: PRERENDERED_SEPARATIONS, useValue: prerendered }] : []),
      ],
    });
    const fixture = TestBed.createComponent(LatestSeparations);
    // Not whenStable: the request the block makes once on screen is pending until flushed.
    TestBed.tick();
    await Promise.resolve();
    TestBed.tick();
    const http = TestBed.inject(HttpTestingController);
    const element = fixture.nativeElement as HTMLElement;
    return { fixture, http, element };
  }

  const names = (element: HTMLElement) =>
    [...element.querySelectorAll('.child')].map((child) => child.textContent!.trim());

  it('shows the prerendered events, then the ones the API answers', async () => {
    const { fixture, http, element } = await mount([USA_667]);
    expect(names(element)).toEqual(['USA 667']);

    http.expectOne((r) => r.url === '/api/separations' && r.params.get('limit') === String(HOME_SEPARATIONS))
      .flush(list([YAOGAN, USA_667]));
    await fixture.whenStable();
    expect(names(element)).toEqual(['62 fragments', 'USA 667']);
    http.verify();
  });

  it('gives each event its date at its precision, its kind and a link to its page', async () => {
    const { fixture, http, element } = await mount();
    http.expectOne((r) => r.url === '/api/separations').flush(list([USA_667, YAOGAN]));
    await fixture.whenStable();

    const links = [...element.querySelectorAll<HTMLAnchorElement>('a.event')];
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/separations/S100685', '/separations/S100564']);
    // Only the month is known: no invented day.
    expect(links[0].querySelector('.when')!.textContent).toContain('Sep 2026');
    expect(links[0].querySelector('.when')!.textContent).toContain('to the month · uncertain');
    expect(links[0].querySelector('.tag')!.textContent).toContain('Release');
    expect(links[1].querySelector('.tag')!.textContent).toContain('Fragmentation');
  });

  it('always links to the full list, and never names the source', async () => {
    const { fixture, http, element } = await mount();
    http.expectOne((r) => r.url === '/api/separations').flush(list([USA_667]));
    await fixture.whenStable();

    const all = [...element.querySelectorAll<HTMLAnchorElement>('a')].find((a) => a.textContent!.includes('All separations'));
    expect(all?.getAttribute('href')).toBe('/separations');
    expect(element.textContent).not.toMatch(/GCAT|McDowell|planet4589/);
  });

  it('asks the API nothing until the block comes near the screen', async () => {
    vi.stubGlobal('IntersectionObserver', class { observe() {} disconnect() {} });
    const { http, element } = await mount([USA_667]);
    http.expectNone(() => true);
    expect(names(element)).toEqual(['USA 667']);
  });

  it('says so when the API fails, and keeps the way to the full list', async () => {
    const { fixture, http, element } = await mount();
    http.expectOne((r) => r.url === '/api/separations').flush(null, { status: 502, statusText: 'Bad Gateway' });
    await fixture.whenStable();

    expect(element.textContent).toContain('could not be loaded');
    expect(element.textContent).toContain('All separations');
  });

  describe('counts the events opened from the home page', () => {
    const sendBeacon = vi.fn(() => true);
    beforeEach(() => { sendBeacon.mockClear(); vi.stubGlobal('navigator', { ...navigator, sendBeacon }); });

    it('as home-open-event', async () => {
      const { fixture, http, element } = await mount();
      http.expectOne((r) => r.url === '/api/separations').flush(list([USA_667]));
      await fixture.whenStable();
      // Ctrl-click: counted like any click, without the router leaving the page under test.
      element.addEventListener('click', (e) => e.preventDefault());
      element.querySelector('a.event')!.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }));
      expect(sendBeacon).toHaveBeenCalledExactlyOnceWith('/api/usage/home-open-event');
    });
  });
});
