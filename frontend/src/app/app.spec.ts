import { describe, beforeEach, afterEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

/**
 * The shell before anything has been asked of the backend.
 *
 * The HTTP layer is a double and no request is expected: the point of this test is that
 * the page is useful with an empty resource. A first render that fires a request nobody
 * asked for would show up here as an unexpected call.
 */
describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
  });

  it('should render the application title', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const heading = fixture.nativeElement.querySelector('h1') as HTMLElement;
    expect(heading.textContent).toContain('Sat Pass Predictor');
  });

  it('starts on the idle state rather than a spinner', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const state = fixture.nativeElement.querySelector('.state') as HTMLElement;
    expect(state.textContent).toContain('Pick a satellite');
  });

  it('offers the Lyon defaults, which are the ones the API applies', async () => {
    const fixture = TestBed.createComponent(App);
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
describe('App geolocation', () => {
  function installGeolocation(implementation: Partial<Geolocation>) {
    Object.defineProperty(navigator, 'geolocation', {
      value: implementation,
      configurable: true,
    });
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
  });

  afterEach(() => {
    delete (navigator as unknown as Record<string, unknown>)['geolocation'];
  });

  async function clickLocate() {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    (fixture.nativeElement.querySelector('button.ghost') as HTMLButtonElement).click();
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

  it('says so when the browser has no geolocation at all', async () => {
    const fixture = await clickLocate();

    expect(fixture.nativeElement.querySelector('.location-error').textContent)
      .toContain('does not offer geolocation');
  });
});
