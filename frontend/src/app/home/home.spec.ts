import { describe, beforeEach, afterEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HomePage, queryFromUrl } from './home';

/**
 * The shell before anything has been asked of the backend.
 *
 * The HTTP layer is a double and no request is expected: the point of this test is that
 * the page is useful with an empty resource. A first render that fires a request nobody
 * asked for would show up here as an unexpected call.
 */
describe('HomePage', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HomePage],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
  });

  it('starts on the idle state rather than a spinner', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await fixture.whenStable();
    const state = fixture.nativeElement.querySelector('.state') as HTMLElement;
    expect(state.textContent).toContain('Pick a satellite');
  });

  it('offers the Lyon defaults, which are the ones the API applies', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await fixture.whenStable();
    const inputs = fixture.nativeElement.querySelectorAll('input') as NodeListOf<HTMLInputElement>;
    expect(inputs[0].value).toBe('25544');
    expect(inputs[1].value).toBe('45.7578');
  });
});

/**
 * Geolocation is a permission the user can refuse, a sensor that can fail and a call that
 * can hang. What these tests pin is that none of the three costs the user the page: the
 * two fields stay editable, and every failure says something.
 */
describe('HomePage geolocation', () => {
  function installGeolocation(implementation: Partial<Geolocation>) {
    Object.defineProperty(navigator, 'geolocation', {
      value: implementation,
      configurable: true,
    });
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HomePage],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
  });

  afterEach(() => {
    delete (navigator as unknown as Record<string, unknown>)['geolocation'];
  });

  async function clickLocate() {
    const fixture = TestBed.createComponent(HomePage);
    await fixture.whenStable();
    (fixture.nativeElement.querySelector('.console-foot button.outline') as HTMLButtonElement).click();
    await fixture.whenStable();
    return fixture;
  }

  it('fills the position, rounded to about ten metres', async () => {
    installGeolocation({
      getCurrentPosition: (onSuccess) =>
        onSuccess({
          coords: { latitude: 45.764_043_21, longitude: 4.835_659_87, altitude: 197.4 },
        } as GeolocationPosition),
    });

    const inputs = (await clickLocate()).nativeElement
      .querySelectorAll('input') as NodeListOf<HTMLInputElement>;

    expect(inputs[1].value).toBe('45.764');
    expect(inputs[2].value).toBe('4.8357');
    // The Geolocation API measures altitude above the WGS84 ellipsoid, which is the datum
    // ObserverLocation expects: it goes in as it comes, only rounded.
    expect(inputs[3].value).toBe('197');
  });

  it('keeps the altitude that was typed when the device does not measure one', async () => {
    installGeolocation({
      getCurrentPosition: (onSuccess) =>
        onSuccess({
          coords: { latitude: 45.76, longitude: 4.84, altitude: null },
        } as GeolocationPosition),
    });

    const inputs = (await clickLocate()).nativeElement
      .querySelectorAll('input') as NodeListOf<HTMLInputElement>;

    expect(inputs[3].value).toBe('170');
  });

  it('says so when the permission is refused, and leaves the fields alone', async () => {
    installGeolocation({
      getCurrentPosition: (_onSuccess, onError) =>
        onError?.({ code: 1, message: 'denied' } as GeolocationPositionError),
    });

    const fixture = await clickLocate();

    expect(fixture.nativeElement.querySelector('.location-error').textContent)
      .toContain('Permission refused');
    const inputs = fixture.nativeElement.querySelectorAll('input') as NodeListOf<HTMLInputElement>;
    expect(inputs[1].value).toBe('45.7578');
    expect(inputs[1].disabled).toBe(false);
  });

  it('only fills the form when nothing has been computed yet', async () => {
    installGeolocation({
      getCurrentPosition: (onSuccess) =>
        onSuccess({ coords: { latitude: 48.8566, longitude: 2.3522, altitude: null } } as GeolocationPosition),
    });

    await clickLocate();

    TestBed.inject(HttpTestingController).expectNone(() => true);
  });

  it('computes again for the located position when a result is on screen', async () => {
    installGeolocation({
      getCurrentPosition: (onSuccess) =>
        onSuccess({ coords: { latitude: 48.856_61, longitude: 2.352_22, altitude: 35 } } as GeolocationPosition),
    });
    const fixture = TestBed.createComponent(HomePage);
    await fixture.whenStable();
    (fixture.nativeElement.querySelector('form.query') as HTMLFormElement)
      .dispatchEvent(new Event('submit', { cancelable: true }));
    TestBed.tick();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(request => request.url === '/api/passes' && request.params.get('lat') === '45.7578')
      .flush(null, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();

    (fixture.nativeElement.querySelector('.console-foot button.outline') as HTMLButtonElement).click();
    await Promise.resolve();
    TestBed.tick();

    const located = http.expectOne(request => request.url === '/api/passes');
    expect(located.request.params.get('lat')).toBe('48.8566');
    expect(located.request.params.get('lon')).toBe('2.3522');
    expect(located.request.params.get('alt')).toBe('35');
  });

  it('says so when the browser has no geolocation at all', async () => {
    const fixture = await clickLocate();

    expect(fixture.nativeElement.querySelector('.location-error').textContent)
      .toContain('does not offer geolocation');
  });

  it('refuses to compute while the satellite field holds a name nobody chose', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await fixture.whenStable();
    const satellite = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    satellite.value = 'iss';
    satellite.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form.query').dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Choose a satellite');
    // The previous number (25544) must not be computed under a name the user typed.
    TestBed.inject(HttpTestingController).expectNone('/api/passes');
  });

  it('asks for the Doppler shift only when a downlink frequency is given', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await fixture.whenStable();
    const frequency = fixture.nativeElement.querySelector('input.w-freq') as HTMLInputElement;
    const submit = () => {
      fixture.nativeElement.querySelector('form.query').dispatchEvent(new Event('submit', { cancelable: true }));
      TestBed.tick();
      return TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/passes');
    };

    frequency.value = '145.8';
    frequency.dispatchEvent(new Event('input'));
    const withFrequency = submit();
    expect(withFrequency.request.params.get('frequencyMhz')).toBe('145.8');

    // Emptied, the field means "no frequency": the parameter is left out, never sent as 0.
    frequency.value = '';
    frequency.dispatchEvent(new Event('input'));
    const without = submit();
    expect(without.request.params.has('frequencyMhz')).toBe(false);
  });
});

describe('a shared link', () => {
  const params = (entries: Record<string, string>) => ({ get: (name: string) => entries[name] ?? null });

  it('names no search without a satellite', () => {
    expect(queryFromUrl(params({ lat: '10' }))).toBeUndefined();
  });

  it('reads the fields it carries over the defaults, and ignores the ones it garbles', () => {
    expect(queryFromUrl(params({ norad: '20580', lat: '-33.92', lon: 'abc', minEl: '25' }))).toEqual({
      noradId: 20580, lat: -33.92, lon: 4.832, alt: 170, hours: 48, minElevation: 25,
    });
  });

  it('carries the downlink frequency when the link names one', () => {
    expect(queryFromUrl(params({ norad: '25544', freq: '145.8' }))?.frequencyMhz).toBe(145.8);
  });

  it('opens on its result: the form is filled and the prediction requested once', async () => {
    await TestBed.configureTestingModule({
      imports: [HomePage],
      providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    const route = TestBed.inject(ActivatedRoute);
    Object.defineProperty(route, 'snapshot', {
      value: { queryParamMap: convertToParamMap({ norad: '48274', lat: '43.3', lon: '5.4' }) },
    });
    const fixture = TestBed.createComponent(HomePage);
    TestBed.tick();
    const request = TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/passes');
    expect(request.request.params.get('noradId')).toBe('48274');
    request.flush({
      satellite: { noradId: 48274, name: 'CSS (TIANHE)' },
      tle: { epoch: '2026-09-30T00:00:00Z', ageSeconds: 3600, source: 'test', fetchedAt: '2026-09-30T00:00:00Z', line1: '', line2: '' },
      observer: { latitudeDeg: 43.3, longitudeDeg: 5.4, altitudeM: 170 },
      minElevationDeg: 10, frequencyMhz: null, computedAt: '2026-09-30T01:00:00Z', passes: [],
    });
    await fixture.whenStable();
    const inputs = fixture.nativeElement.querySelectorAll('input') as NodeListOf<HTMLInputElement>;
    expect(inputs[1].value).toBe('43.3');
    expect(fixture.nativeElement.querySelector('.state').textContent).toContain('No pass above 10');
  });
});
