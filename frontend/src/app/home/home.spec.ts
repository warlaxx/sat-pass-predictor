import { describe, beforeEach, afterEach, it, expect, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HomePage, hasAdvancedSettings, queryFromUrl } from './home';
import { DEFAULT_QUERY } from '../api/passes.query';
import { PassesResponse } from '../api/passes.model';

/**
 * Lets a freshly created page settle. It asks for the hero's featured passes as it opens,
 * and a pending request keeps it from ever being stable: they are answered with `body`,
 * or with the API down when a test is about something else.
 */
async function settle(fixture: ComponentFixture<HomePage>, body?: PassesResponse): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  for (const request of TestBed.inject(HttpTestingController).match('/api/featured-pass')) {
    if (body) request.flush(body);
    else request.flush(null, { status: 503, statusText: 'Service Unavailable' });
  }
  await fixture.whenStable();
}

/**
 * The shell before the reader has asked anything of the backend.
 *
 * The HTTP layer is a double. The only request a first render may make is the hero's
 * featured passes, which are not metered (ABD-31); a search nobody asked for would spend
 * the quota the whole site shares, and shows up here as a call to /api/passes.
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
    await settle(fixture);
    const state = fixture.nativeElement.querySelector('.state') as HTMLElement;
    expect(state.textContent).toContain('Pick a satellite');
  });

  it('counts down to the ISS over Lyon as it opens, without searching or filling the result', async () => {
    const rise = Date.now() + 3_600_000;
    const point = (offset: number) => ({
      instant: new Date(rise + offset * 1000).toISOString(), azimuthDeg: 300, elevationDeg: 40, rangeKm: 800,
      rangeRateKmS: 0, dopplerHz: null, subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 },
      illuminated: true, visible: true,
    });
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture, {
      satellite: { noradId: 25544, name: 'ISS (ZARYA)' },
      tle: { epoch: new Date().toISOString(), ageSeconds: 3600, source: 'test', fetchedAt: new Date().toISOString(), line1: '', line2: '' },
      observer: { latitudeDeg: 45.7578, longitudeDeg: 4.832, altitudeM: 170 },
      minElevationDeg: 10, frequencyMhz: null, computedAt: new Date().toISOString(),
      passes: [{ aos: point(0), culmination: point(200), los: point(400), durationSeconds: 400, track: [point(0), point(200), point(400)] }],
    });

    TestBed.inject(HttpTestingController).expectNone('/api/passes');
    const card = fixture.nativeElement.querySelector('app-hero .card') as HTMLElement;
    expect(card.textContent).toContain('ISS (ZARYA) over Lyon');
    expect(card.querySelector('.countdown')!.textContent).toMatch(/T−0[01]:\d\d:\d\d/);
    // The reader has computed nothing: the console still says what to do.
    expect(fixture.nativeElement.querySelector('.state').textContent).toContain('Pick a satellite');
  });

  /** ABD-37: a beginner sees a satellite, a place and the button; the rest is folded. */
  it('folds the advanced settings and names the place in words', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture);
    const details = fixture.nativeElement.querySelector('details.advanced') as HTMLDetailsElement;
    expect(details.open).toBe(false);
    expect(details.querySelector('summary')!.textContent).toContain('Advanced settings');
    // Latitude, longitude, altitude, window, threshold and downlink are all inside.
    expect(details.querySelectorAll('input').length).toBe(6);
    expect(fixture.nativeElement.querySelector('.place-name').textContent).toContain('Lyon (default)');
  });

  it('offers the Lyon defaults, which are the ones the API applies', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture);
    const inputs = fixture.nativeElement.querySelectorAll('input') as NodeListOf<HTMLInputElement>;
    // The default satellite by name; its number is beside it (ABD-37).
    expect(inputs[0].value).toBe('ISS (ZARYA)');
    expect(fixture.nativeElement.querySelector('.satellite .unit').textContent).toContain('NORAD 25544');
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
    await settle(fixture);
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

    TestBed.inject(HttpTestingController).expectNone('/api/passes');
  });

  it('computes for the located position in one click from the hero, before any search', async () => {
    installGeolocation({
      getCurrentPosition: (onSuccess) =>
        onSuccess({ coords: { latitude: 48.856_61, longitude: 2.352_22, altitude: 35 } } as GeolocationPosition),
    });
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture);

    (fixture.nativeElement.querySelector('app-hero .locate') as HTMLButtonElement).click();
    await Promise.resolve();
    TestBed.tick();

    const located = TestBed.inject(HttpTestingController).expectOne(request => request.url === '/api/passes');
    expect(located.request.params.get('lat')).toBe('48.8566');
    expect(located.request.params.get('lon')).toBe('2.3522');
  });

  it('says why the hero could not locate, in the hero', async () => {
    installGeolocation({
      getCurrentPosition: (_onSuccess, onError) =>
        onError?.({ code: 1, message: 'denied' } as GeolocationPositionError),
    });
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture);

    (fixture.nativeElement.querySelector('app-hero .locate') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('app-hero .location-error').textContent).toContain('Permission refused');
    expect(fixture.nativeElement.querySelector('#query .location-error')).toBeNull();
  });

  it('computes again for the located position when a result is on screen', async () => {
    installGeolocation({
      getCurrentPosition: (onSuccess) =>
        onSuccess({ coords: { latitude: 48.856_61, longitude: 2.352_22, altitude: 35 } } as GeolocationPosition),
    });
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture);
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
    await settle(fixture);
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
    await settle(fixture);
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

  it('computes from the hero button and brings the console into view', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture);
    const console = fixture.nativeElement.querySelector('#query') as HTMLElement;
    const scrolled = vi.fn();
    console.scrollIntoView = scrolled;

    (fixture.nativeElement.querySelector('app-hero .ctas button') as HTMLButtonElement).click();
    TestBed.tick();

    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/passes');
    expect(scrolled).toHaveBeenCalled();
  });

  it('computes again when the same query is asked twice', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture);
    const http = TestBed.inject(HttpTestingController);
    const hero = fixture.nativeElement.querySelector('app-hero .ctas button') as HTMLButtonElement;
    (fixture.nativeElement.querySelector('#query') as HTMLElement).scrollIntoView = vi.fn();

    hero.click();
    TestBed.tick();
    http.expectOne((r) => r.url === '/api/passes').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    hero.click();
    TestBed.tick();
    http.expectOne((r) => r.url === '/api/passes');
  });

  it('writes the URL of a search without sending the reader back to the top', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await settle(fixture);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');

    fixture.nativeElement.querySelector('form.query').dispatchEvent(new Event('submit', { cancelable: true }));
    TestBed.tick();

    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ scroll: 'manual' }));
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

  it('opens the advanced settings only when they hold something other than the defaults', () => {
    expect(hasAdvancedSettings(DEFAULT_QUERY)).toBe(false);
    // A place alone is shown in words, without unfolding anything.
    expect(hasAdvancedSettings({ ...DEFAULT_QUERY, lat: 48.85, lon: 2.35 })).toBe(false);
    expect(hasAdvancedSettings({ ...DEFAULT_QUERY, hours: 72 })).toBe(true);
    expect(hasAdvancedSettings({ ...DEFAULT_QUERY, minElevation: 25 })).toBe(true);
    expect(hasAdvancedSettings({ ...DEFAULT_QUERY, frequencyMhz: 145.8 })).toBe(true);
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
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne((r) => r.url === '/api/passes');
    expect(request.request.params.get('noradId')).toBe('48274');
    request.flush({
      satellite: { noradId: 48274, name: 'CSS (TIANHE)' },
      tle: { epoch: '2026-09-30T00:00:00Z', ageSeconds: 3600, source: 'test', fetchedAt: '2026-09-30T00:00:00Z', line1: '', line2: '' },
      observer: { latitudeDeg: 43.3, longitudeDeg: 5.4, altitudeM: 170 },
      minElevationDeg: 10, frequencyMhz: null, computedAt: '2026-09-30T01:00:00Z', passes: [],
    });
    await fixture.whenStable();
    http.expectNone('/api/featured-pass');
    const inputs = fixture.nativeElement.querySelectorAll('input') as NodeListOf<HTMLInputElement>;
    expect(inputs[1].value).toBe('43.3');
    expect(fixture.nativeElement.querySelector('.state').textContent).toContain('No pass above 10');
    expect(fixture.nativeElement.querySelector('.place-name').textContent).toContain('43.3000°, 5.4000°');
    expect((fixture.nativeElement.querySelector('details.advanced') as HTMLDetailsElement).open).toBe(false);
  });
});
