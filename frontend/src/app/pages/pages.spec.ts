import { describe, beforeEach, afterEach, it, expect, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { PLATFORM_ID, provideZonelessChangeDetection, Type } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { PassesResponse } from '../api/passes.model';
import { SatellitePage } from './satellite/satellite';
import { StatusPage } from './status/status';
import { AlertsPage } from './alerts/alerts';
import { LegalPage } from './legal/legal';
import { StarlinkPage } from './starlink/starlink';
import { OPERATOR } from '../shared/site';

const ISS_LINE_1 = '1 25544U 98067A   21035.14486477  .00001026  00000-0  26816-4 0  9998';
const ISS_LINE_2 = '2 25544  51.6455 280.7636 0002243 335.6496 186.1723 15.48938788267977';

/** A response with one pass, sunlit in a dark sky or not. */
function prediction(visible = true): PassesResponse {
  const point = (instant: string, elevationDeg: number) => ({
    instant, azimuthDeg: 200, elevationDeg, rangeKm: 800, rangeRateKmS: -1, dopplerHz: null,
    subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 }, illuminated: visible, visible,
  });
  const aos = point('2026-10-01T19:00:00Z', 10);
  const culmination = point('2026-10-01T19:03:00Z', 60);
  const los = point('2026-10-01T19:06:00Z', 10);
  return {
    satellite: { noradId: 25544, name: 'ISS (ZARYA)' },
    tle: { epoch: '2026-09-30T00:00:00Z', ageSeconds: 3600, source: 'CelesTrak', fetchedAt: '2026-09-30T01:00:00Z', line1: ISS_LINE_1, line2: ISS_LINE_2 },
    observer: { latitudeDeg: 45.7578, longitudeDeg: 4.832, altitudeM: 170 },
    minElevationDeg: 10, frequencyMhz: null, computedAt: '2026-09-30T02:00:00Z',
    passes: [{ aos, culmination, los, durationSeconds: 360, track: [aos, culmination, los] }],
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

/** Lets a resource take its answer in, then runs change detection: the next request leaves. */
async function settle() {
  await new Promise((resolve) => setTimeout(resolve));
  TestBed.tick();
}

describe('StarlinkPage', () => {
  beforeEach(configure);

  const launches = {
    catalogFetchedAt: '2026-10-01T10:00:00Z',
    launches: [
      { designator: '2026-045', satellites: 28, noradId: 64001, name: 'STARLINK-34001' },
      { designator: '2026-041', satellites: 1, noradId: 63990, name: 'STARLINK-33990' },
    ],
  };

  it('shows the passes of the newest launch, standing for its train', async () => {
    const fixture = await render(StarlinkPage);
    const http = TestBed.inject(HttpTestingController);
    const lookup = http.expectOne((r) => r.url === '/api/satellites/launches');
    expect(lookup.request.params.get('q')).toBe('starlink');
    lookup.flush(launches);
    // Not whenStable: the passes request it waits for is the one this test answers.
    await settle();

    const request = http.expectOne((r) => r.url === '/api/passes');
    expect(request.request.params.get('noradId')).toBe('64001');
    expect(request.request.params.get('hours')).toBe('72');
    request.flush({ ...prediction(), satellite: { noradId: 64001, name: 'STARLINK-34001' } });
    await fixture.whenStable();

    const page = fixture.nativeElement as HTMLElement;
    const buttons = page.querySelectorAll<HTMLButtonElement>('.launches button');
    expect(buttons).toHaveLength(2);
    expect(buttons[0].getAttribute('aria-pressed')).toBe('true');
    expect(buttons[0].textContent).toContain('28 satellites');
    expect(buttons[1].textContent).toContain('1 satellite');
    expect(page.querySelectorAll('app-pass-table tbody tr')).toHaveLength(1);
    expect(page.textContent).toContain('1 potentially visible');
  });

  it('switches to an older launch on request', async () => {
    const fixture = await render(StarlinkPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne((r) => r.url === '/api/satellites/launches').flush(launches);
    await settle();
    http.expectOne((r) => r.url === '/api/passes').flush(prediction());
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.launches button')[1].click();
    TestBed.tick();
    expect(http.expectOne((r) => r.url === '/api/passes').request.params.get('noradId')).toBe('63990');
  });

  it('says so when the catalogue holds no launch', async () => {
    const fixture = await render(StarlinkPage);
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/satellites/launches')
      .flush({ catalogFetchedAt: '2026-10-01T10:00:00Z', launches: [] });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No Starlink launch in the catalogue');
    TestBed.inject(HttpTestingController).expectNone((r) => r.url === '/api/passes');
  });
});

describe('SatellitePage', () => {
  beforeEach(configure);

  it('shows the name, the orbit and the passes from one request', async () => {
    const fixture = await render(SatellitePage, { noradId: '25544' });
    const request = TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/passes');
    expect(request.request.params.get('noradId')).toBe('25544');
    expect(request.request.params.get('hours')).toBe('72');
    request.flush(prediction());
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('#sat-title')!.textContent).toContain('ISS (ZARYA)');
    expect(page.querySelector('.page-hero')!.textContent).toContain('Low Earth orbit');
    expect(page.querySelector('.facts')!.textContent).toContain('51.65°');
    expect(page.querySelectorAll('app-pass-table tbody tr')).toHaveLength(1);
    expect(page.textContent).toContain('1 potentially visible');
  });

  it('names a featured satellite and describes it before any answer arrives', async () => {
    const fixture = await render(SatellitePage, { noradId: '20580' });
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('#sat-title')!.textContent).toContain('Hubble Space Telescope');
    expect(page.querySelector('.lede')!.textContent).toContain('28.5°');
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/passes');
  });

  it('titles a satellite outside the featured list by its catalogue name once known', async () => {
    const fixture = await render(SatellitePage, { noradId: '43013' });
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/passes')
      .flush({ ...prediction(), satellite: { noradId: 43013, name: 'NOAA 20' } });
    await fixture.whenStable();
    expect(TestBed.inject(Title).getTitle()).toBe('NOAA 20: next passes and when to see it · NextPass');
  });

  it('computes no pass while prerendering: they would be stale before anyone read them', async () => {
    TestBed.overrideProvider(PLATFORM_ID, { useValue: 'server' });
    const fixture = await render(SatellitePage, { noradId: '25544' });
    await fixture.whenStable();
    TestBed.inject(HttpTestingController).expectNone((r) => r.url === '/api/passes');
    expect((fixture.nativeElement as HTMLElement).querySelector('#sat-title')!.textContent).toContain('International Space Station');
  });

  it('refuses a number that is not one, without asking the backend', async () => {
    const fixture = await render(SatellitePage, { noradId: 'abc' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('not a NORAD number');
    TestBed.inject(HttpTestingController).expectNone('/api/passes');
  });

  it('says what the backend said when the satellite does not exist', async () => {
    const fixture = await render(SatellitePage, { noradId: '99999' });
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/passes').flush(
      { type: 'x/unknown-satellite', title: 'Unknown satellite', status: 404, detail: 'No satellite 99999.' },
      { status: 404, statusText: 'Not Found' },
    );
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Unknown satellite');
  });
});

describe('StatusPage', () => {
  beforeEach(configure);

  it('probes the health endpoint, then the catalogue, and never the pass endpoint', async () => {
    const fixture = await render(StatusPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/status-probe/health').flush({ status: 'UP' });
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne((r) => r.url === '/api/satellites').flush({
      results: [{ noradId: 25544, name: 'ISS (ZARYA)' }], catalogFetchedAt: new Date(Date.now() - 3_600_000).toISOString(),
    });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('#status-title')!.textContent).toContain('All systems answering');
    expect(page.querySelectorAll('.dot.ok')).toHaveLength(3);
    http.expectNone((r) => r.url === '/api/passes');
  });

  it('reports a backend that does not answer', async () => {
    const fixture = await render(StatusPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/status-probe/health').flush('', { status: 502, statusText: 'Bad Gateway' });
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne((r) => r.url === '/api/satellites').flush('', { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('#status-title').textContent).toContain('not answering');
    expect(fixture.nativeElement.textContent).toContain('NORAD numbers still work');
  });
});

describe('AlertsPage', () => {
  beforeEach(configure);
  afterEach(() => vi.restoreAllMocks());

  async function search(response: PassesResponse) {
    const fixture = await render(AlertsPage);
    await fixture.whenStable();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { cancelable: true }));
    TestBed.tick();
    const request = TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/passes');
    expect(request.request.params.get('hours')).toBe('168');
    request.flush(response);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('lists the visible passes and downloads them as a calendar without another request', async () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:calendar');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const page = await search(prediction());
    expect(page.querySelectorAll('.window')).toHaveLength(1);
    (page.querySelector('.section-head .btn.primary') as HTMLButtonElement).click();
    expect(clicked).toHaveBeenCalledOnce();
    TestBed.inject(HttpTestingController).verify();
  });

  it('explains an empty result instead of offering an empty file', async () => {
    const page = await search(prediction(false));
    expect(page.querySelector('.window')).toBeNull();
    expect(page.textContent).toContain('none in a dark sky');
  });
});

describe('LegalPage', () => {
  beforeEach(configure);

  it('marks every missing operator fact rather than inventing one', async () => {
    const fixture = await render(LegalPage);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('.note')!.textContent).toContain('Draft');
    // One marker per blank field of OPERATOR, whichever of them have been filled in.
    const blank = Object.values(OPERATOR).filter((value) => !value).length;
    expect(page.querySelectorAll('#notice dd .todo').length).toBe(blank);
    expect(page.querySelector('#privacy')).not.toBeNull();
    expect(page.querySelector('#terms')).not.toBeNull();
  });
});
