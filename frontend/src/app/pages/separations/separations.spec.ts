import { beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection, Type } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { PassesResponse } from '../../api/passes.model';
import { SeparationEvent, SeparationsResponse, SpaceObject } from '../../api/separations.model';
import { SeparationPage } from '../separation/separation';
import { SeparationsPage } from './separations';

/** Real events of the record, as the backend answered them on 2 October 2026. */
const LIST: SeparationsResponse = {
  events: [
    {
      id: 'S100685', kind: 'RELEASE', date: { text: '2026 Sep?', at: '2026-09-01T00:00:00Z', precision: 'MONTH', uncertain: true },
      parentName: 'USA 396', parentOwner: 'AFRL', parentState: 'US', firstChildName: 'USA 667', firstChildNoradId: 100685,
      children: 1, orbit: { perigeeKm: 35800, apogeeKm: 36100, inclinationDeg: 0, orbitClass: 'GEO/D' }, inOrbit: true,
    },
    {
      id: 'S100564', kind: 'FRAGMENTATION', date: { text: '2026 Jul 20?', at: '2026-07-20T00:00:00Z', precision: 'DAY', uncertain: true },
      parentName: 'Yaogan 50 hao 02 xing', parentOwner: 'ZXW', parentState: 'CN', firstChildName: 'deb YG50-02', firstChildNoradId: 100564,
      children: 62, orbit: { perigeeKm: 593, apogeeKm: 1085, inclinationDeg: 97, orbitClass: 'LEO/R' }, inOrbit: true,
    },
    {
      id: 'S69237', kind: 'RELEASE', date: { text: '2026 May 27 2000', at: '2026-05-27T20:00:00Z', precision: 'MINUTE', uncertain: false },
      parentName: 'Poisk', parentOwner: 'RKKE', parentState: 'RU', firstChildName: 'VKD-66 window cleaner bundle', firstChildNoradId: 69237,
      children: 1, orbit: { perigeeKm: 410, apogeeKm: 416, inclinationDeg: 51.6, orbitClass: 'LLEO/I' }, inOrbit: false,
    },
  ],
  stats: {
    objects: 28364, objectsThisYear: 105, year: 2026,
    byMonth: [
      { month: '2026-05', releases: 4, fragmentations: 2 },
      { month: '2026-06', releases: 2, fragmentations: 1 },
      { month: '2026-07', releases: 2, fragmentations: 2 },
      { month: '2026-08', releases: 0, fragmentations: 0 },
    ],
  },
  updatedAt: '2026-10-02T16:42:00Z',
};

function object(overrides: Partial<SpaceObject>): SpaceObject {
  return {
    id: 'S69328', noradId: 69328, name: 'Shenzhou 22 Guidao Cang', payloadName: 'Shenzhou 22 orbital module',
    piece: '2025-272C', role: 'payload', owner: 'CMSEO', state: 'CN', massKg: 1842,
    launch: { text: '2025 Nov 25', at: '2025-11-25T00:00:00Z', precision: 'DAY', uncertain: false },
    orbit: { perigeeKm: 382, apogeeKm: 394, inclinationDeg: 41.48, orbitClass: 'LLEO/I' }, inOrbit: true,
    evidence: {
      id: 'S69328', satcat: 69328, piece: '2025-272C', name: 'Shenzhou 22 Guidao Cang', payloadName: 'Shenzhou 22 orbital module',
      parent: 'S66645', separationDate: '2026 May 29 1120', owner: 'CMSEO', status: 'O',
    },
    ...overrides,
  };
}

const SHENZHOU: SeparationEvent = {
  id: 'S69328', kind: 'RELEASE',
  date: { text: '2026 May 29 1120', at: '2026-05-29T11:20:00Z', precision: 'MINUTE', uncertain: false },
  parent: object({ id: 'S66645', noradId: 66645, name: 'Shenzhou 22', payloadName: 'Shenzhou 22', piece: '2025-272A', massKg: 3258 }),
  grandparent: object({ id: 'S66646', noradId: 66646, name: 'CZ-2F Y22 Stage 2', role: 'rocket-stage', inOrbit: false }),
  children: [object({})], childCount: 1, updatedAt: '2026-10-02T16:42:00Z',
};

function prediction(): PassesResponse {
  const point = (instant: string, elevationDeg: number) => ({
    instant, azimuthDeg: 196, elevationDeg, rangeKm: 1200, rangeRateKmS: -1, dopplerHz: null,
    subPoint: { latitudeDeg: 40, longitudeDeg: 2, altitudeKm: 390 }, illuminated: true, visible: true,
  });
  const aos = point('2026-10-04T04:25:12Z', 10);
  const culmination = point('2026-10-04T04:26:54Z', 14);
  const los = point('2026-10-04T04:28:37Z', 10);
  return {
    satellite: { noradId: 69328, name: 'SZ-22 MODULE' },
    tle: {
      epoch: '2026-10-01T16:22:40Z', ageSeconds: 3600, source: 'CelesTrak', fetchedAt: '2026-10-01T17:00:00Z',
      line1: '1 25544U 98067A   21035.14486477  .00001026  00000-0  26816-4 0  9998',
      line2: '2 25544  51.6455 280.7636 0002243 335.6496 186.1723 15.48938788267977',
    },
    observer: { latitudeDeg: 45.7578, longitudeDeg: 4.832, altitudeM: 170 },
    minElevationDeg: 10, frequencyMhz: null, computedAt: '2026-10-02T18:00:00Z',
    passes: [{ aos, culmination, los, durationSeconds: 205, track: [aos, culmination, los] }],
  };
}

function configure() {
  return TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
  }).compileComponents();
}

async function render<T>(component: Type<T>, inputs: Record<string, unknown> = {}) {
  const fixture = TestBed.createComponent(component);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  TestBed.tick();
  return fixture;
}

async function settle() {
  await new Promise((resolve) => setTimeout(resolve));
  TestBed.tick();
}

describe('SeparationsPage', () => {
  beforeEach(configure);

  async function loaded() {
    const fixture = await render(SeparationsPage);
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/separations').flush(LIST);
    await fixture.whenStable();
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }

  it('lists the events by month with the precision each date has', async () => {
    const { page } = await loaded();

    expect([...page.querySelectorAll('h3.month')].map((h) => h.textContent?.trim())).toEqual(['September 2026', 'July 2026', 'May 2026']);
    const rows = page.querySelectorAll('a.event');
    expect(rows).toHaveLength(3);
    expect(rows[0].getAttribute('href')).toBe('/separations/S100685');
    expect(rows[0].textContent).toContain('USA 396');
    expect(rows[0].textContent).toContain('USA 667');
    expect(rows[0].textContent).toContain('to the month · uncertain');
    expect(rows[0].textContent).toContain('Holds a fixed point in the sky');
    expect(rows[1].textContent).toContain('62 fragments');
    expect(rows[1].textContent).toContain('593 × 1 085 km');
    expect(rows[2].textContent).toContain('No longer in orbit');
  });

  it('filters releases from fragmentations, with their counts', async () => {
    const { fixture, page } = await loaded();
    const buttons = page.querySelectorAll<HTMLButtonElement>('.filters button');
    expect([...buttons].map((b) => b.textContent?.replace(/\s+/g, ' ').trim())).toEqual(['All 3', 'Releases 2', 'Fragmentations 1']);

    buttons[2].click();
    await fixture.whenStable();

    expect(buttons[2].getAttribute('aria-pressed')).toBe('true');
    expect(page.querySelectorAll('a.event')).toHaveLength(1);
    expect(page.querySelector('a.event')!.textContent).toContain('Yaogan');
  });

  it('charts this year by month, and offers the same figures as a table', async () => {
    const { page } = await loaded();

    expect(page.querySelectorAll('.chart .column')).toHaveLength(4);
    expect(page.querySelectorAll('.chart .segment.fragmentation')).toHaveLength(3);
    expect([...page.querySelectorAll('.chart .total')].map((t) => t.textContent)).toEqual(['6', '3', '4', '0']);
    expect(page.querySelectorAll('.table-view tbody tr')).toHaveLength(4);
    expect(page.querySelector('.facts')!.textContent).toContain('28,364');
  });
});

describe('SeparationPage', () => {
  beforeEach(configure);

  it('shows the lineage, both orbits, the record and the passes of the released object', async () => {
    const fixture = await render(SeparationPage, { id: 'S69328' });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/separations/S69328').flush(SHENZHOU);
    await settle();
    const request = http.expectOne((r) => r.url === '/api/passes');
    expect(request.request.params.get('noradId')).toBe('69328');
    request.flush(prediction());
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('h1')!.textContent).toBe('Shenzhou 22 released Shenzhou 22 Guidao Cang');
    expect(page.querySelector('.lede')!.textContent).toContain('Shenzhou 22 orbital module');
    expect(page.querySelector('.facts')!.textContent).toContain('May 29, 2026 at 11:20 UTC');
    expect(page.querySelector('.facts')!.textContent).toContain('≈ 6 months');
    expect([...page.querySelectorAll('.lineage .name')].map((n) => n.textContent?.trim()))
      .toEqual(['CZ-2F Y22 Stage 2', 'Shenzhou 22', 'Shenzhou 22 Guidao Cang']);
    expect(page.querySelector('.compare')!.textContent).toContain('382 km');
    expect(page.querySelector('.record')!.textContent).toContain('2026 May 29 1120');
    expect(page.querySelectorAll('app-pass-table tbody tr')).toHaveLength(1);
  });

  it('says there is no public orbit rather than guessing', async () => {
    const withheld: SeparationEvent = {
      ...SHENZHOU, id: 'S60999',
      children: [object({ id: 'S60999', noradId: 60999, name: 'USA 999', orbit: { perigeeKm: 35800, apogeeKm: 36100, inclinationDeg: 0, orbitClass: 'GEO/D' } })],
    };
    const fixture = await render(SeparationPage, { id: 'S60999' });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/separations/S60999').flush(withheld);
    await settle();
    http.expectOne((r) => r.url === '/api/passes').flush(
      { type: 'https://github.com/warlaxx/sat-pass-predictor/errors/unknown-satellite', title: 'Unknown satellite', status: 404, detail: 'no TLE published' },
      { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('No public orbit to follow');
    expect(page.textContent).toContain('It is geostationary');
    expect(page.querySelector('app-pass-table')).toBeNull();
  });

  it('asks for no prediction it cannot give yet', async () => {
    const recent: SeparationEvent = { ...SHENZHOU, id: 'S100685', children: [object({ id: 'S100685', noradId: 100685, name: 'USA 667' })] };
    const fixture = await render(SeparationPage, { id: 'S100685' });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/separations/S100685').flush(recent);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Passes not available yet');
    http.expectNone((r) => r.url === '/api/passes');
  });

  it('lists the fragments of a breakup, each linked to its own page', async () => {
    const breakup: SeparationEvent = {
      ...SHENZHOU, id: 'S69731', kind: 'FRAGMENTATION', parent: null, grandparent: null, childCount: 3,
      children: [69731, 69732, 69733].map((n) => object({ id: `S${n}`, noradId: n, name: 'deb YG-25-03', role: 'debris',
        evidence: { ...object({}).evidence, id: `S${n}`, parent: 'S40340', separationDate: '2026 May?' } })),
      date: { text: '2026 May?', at: '2026-05-01T00:00:00Z', precision: 'MONTH', uncertain: true },
    };
    const fixture = await render(SeparationPage, { id: 'S69731' });
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/separations/S69731').flush(breakup);
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('h1')!.textContent).toBe('3 fragments separated from S40340');
    expect(page.querySelectorAll('.objects tbody tr')).toHaveLength(3);
    expect(page.querySelector('.objects a')!.getAttribute('href')).toBe('/satellites/69731');
    expect(page.querySelector('.facts')!.textContent).toContain('May 2026');
    http.expectNone((r) => r.url === '/api/passes');
  });

  it('links a fragment to its page only when the predictor accepts its number', async () => {
    const recent: SeparationEvent = {
      ...SHENZHOU, id: 'S100564', kind: 'FRAGMENTATION', childCount: 2,
      children: [100564, 100565].map((n) => object({ id: `S${n}`, noradId: n, name: 'deb YG50-02', role: 'debris' })),
    };
    const fixture = await render(SeparationPage, { id: 'S100564' });
    TestBed.inject(HttpTestingController).expectOne('/api/separations/S100564').flush(recent);
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelectorAll('.objects tbody tr')).toHaveLength(2);
    expect(page.querySelector('.objects a')).toBeNull();
    expect(page.querySelector('.objects tbody')!.textContent).toContain('100564');
  });

  it('says plainly when no separation has that record', async () => {
    const fixture = await render(SeparationPage, { id: 'S1' });
    TestBed.inject(HttpTestingController).expectOne('/api/separations/S1').flush(
      { type: 'https://github.com/warlaxx/sat-pass-predictor/errors/separation-not-found', title: 'Separation not found', status: 404 },
      { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No separation is recorded for “S1”');
  });
});
