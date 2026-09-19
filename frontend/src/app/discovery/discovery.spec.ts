import { By } from '@angular/platform-browser';
import { App } from '../app';
import { THREE_LOADER } from '../globe/three-loader';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { Discovery, nextOpportunity, parseSatellites } from './discovery';
import { DEFAULT_QUERY } from '../api/passes.query';
import { PassesResponse, TrackPointDto } from '../api/passes.model';

export function prediction(noradId: number, minute: number, favourable = true): PassesResponse {
  const track: TrackPointDto[] = [0, 10, 20, 30].map((second, index) => ({
    instant: new Date(Date.UTC(2026, 8, 19, 20, minute, second)).toISOString(),
    azimuthDeg: 90, elevationDeg: index === 2 ? 45 : 10, rangeKm: 1000,
    subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 },
    illuminated: true, visible: favourable && (index === 1 || index === 2),
  }));
  return {
    satellite: { noradId, name: `Satellite ${noradId}` },
    observer: { latitudeDeg: 45, longitudeDeg: 5, altitudeM: 170 },
    tle: { epoch: '2026-09-19T00:00:00Z', fetchedAt: '2026-09-19T19:00:00Z', ageSeconds: 72000, source: 'test', line1: '', line2: '' },
    computedAt: '2026-09-19T20:00:00Z', minElevationDeg: 10,
    passes: [{ aos: track[0], culmination: track[2], los: track[3], durationSeconds: 30, track }],
  };
}

describe('opportunity selection', () => {
  it('deduplicates IDs and refuses invalid or unbounded batches', () => {
    expect(parseSatellites('25544, 48274 25544')).toEqual([25544, 48274]);
    for (const value of ['', '0', '1.2', 'NaN', '100000', '1,2,3,4,5,6']) {
      expect(() => parseSatellites(value)).toThrow();
    }
  });
  it('returns the visible samples rather than the whole above-horizon pass', () => {
    const response = prediction(25544, 1);
    expect(nextOpportunity(response)?.start).toBe(response.passes[0].track[1].instant);
    expect(nextOpportunity(response)?.end).toBe(response.passes[0].track[2].instant);
    expect(nextOpportunity(prediction(25544, 1, false))).toBeUndefined();
  });
});

describe('Discovery HTTP flow', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  async function start(ids = '25544, 48274') {
    const fixture = TestBed.createComponent(Discovery);
    fixture.componentRef.setInput('query', DEFAULT_QUERY);
    await fixture.whenStable();
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = ids; input.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { cancelable: true }));
    TestBed.tick();
    return fixture;
  }

  it('queries seven days, ranks by first visible sample, and opens the original prediction', async () => {
    const fixture = await start();
    const requests = http.match(request => request.url === '/api/passes');
    expect(requests).toHaveLength(2);
    for (const request of requests) {
      expect(request.request.params.get('hours')).toBe('168');
      expect(request.request.params.get('lat')).toBe(String(DEFAULT_QUERY.lat));
    }
    const later = prediction(25544, 5), earlier = prediction(48274, 1);
    requests[0].flush(later); requests[1].flush(earlier);
    await fixture.whenStable();
    const articles = fixture.nativeElement.querySelectorAll('article');
    expect(articles[0].textContent).toContain('Satellite 48274');
    let opened: unknown;
    fixture.componentInstance.open.subscribe(value => opened = value);
    articles[0].querySelector('button').click();
    expect(opened).toEqual(nextOpportunity(earlier));
  });

  it('keeps successful results when another satellite fails', async () => {
    const fixture = await start();
    const pending = http.match(request => request.url === '/api/passes');
    pending[0].flush(prediction(25544, 1));
    pending[1].flush({ detail: 'Orbital elements unavailable' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('Orbital elements unavailable');
  });

  it('distinguishes no opportunity from a failed prediction', async () => {
    const fixture = await start('25544');
    http.expectOne(request => request.url === '/api/passes').flush(prediction(25544, 1, false));
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('no favourable sample');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('cancels obsolete requests when restarting', async () => {
    const fixture = await start();
    const requests = http.match(request => request.url === '/api/passes');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit', { cancelable: true }));
    TestBed.tick();
    expect(requests.every(request => request.cancelled)).toBe(true);
    const replacements = http.match(request => request.url === '/api/passes');
    replacements.forEach(request => request.flush(prediction(Number(request.request.params.get('noradId')), 1)));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(2);
  });
});


describe('discovery selection in the application', () => {
  it('opens the selected satellite in the existing views without another HTTP request', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: THREE_LOADER, useValue: () => Promise.reject(new Error('WebGL unavailable in test')) },
    ] });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const discovery = fixture.debugElement.query(By.directive(Discovery)).componentInstance as Discovery;
    discovery.open.emit(nextOpportunity(prediction(48274, 1))!);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.kicker').textContent).toContain('Satellite 48274');
    expect(fixture.nativeElement.querySelectorAll('app-pass-table tbody tr')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('app-pass-viewer')).not.toBeNull();
    TestBed.inject(HttpTestingController).verify();
  });
});
