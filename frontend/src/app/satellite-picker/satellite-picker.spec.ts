import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SEARCH_DEBOUNCE_MS, SatellitePicker } from './satellite-picker';
import { SatelliteMatch, asNoradId } from '../api/satellites.service';

const ISS: SatelliteMatch = { noradId: 25544, name: 'ISS (ZARYA)' };
const OBJECT: SatelliteMatch = { noradId: 49044, name: 'ISS OBJECT XK' };

describe('asNoradId', () => {
  it('accepts what the pass endpoint accepts, and nothing else', () => {
    expect(asNoradId(' 25544 ')).toBe(25544);
    // Six digits since July 2026, up to Z9999 in Alpha-5.
    expect(asNoradId('100685')).toBe(100685);
    expect(asNoradId('339999')).toBe(339999);
    for (const text of ['', '0', '340000', '1000000', 'iss', '25544a', '1.5']) expect(asNoradId(text)).toBeUndefined();
  });
});

describe('SatellitePicker', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  async function create(mode: 'value' | 'add' = 'value') {
    const fixture = TestBed.createComponent(SatellitePicker);
    fixture.componentRef.setInput('label', 'Satellite');
    fixture.componentRef.setInput('mode', mode);
    fixture.componentRef.setInput('initial', '25544');
    const changed: (number | null)[] = [];
    const picked: SatelliteMatch[] = [];
    fixture.componentInstance.changed.subscribe(value => changed.push(value));
    fixture.componentInstance.picked.subscribe(value => picked.push(value));
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    const type = (text: string) => {
      input.value = text;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };
    const key = (name: string) => {
      const event = new KeyboardEvent('keydown', { key: name, cancelable: true });
      input.dispatchEvent(event);
      fixture.detectChanges();
      return event;
    };
    return { fixture, input, type, key, changed, picked };
  }

  function answer(q: string, results: SatelliteMatch[]) {
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    const request = http.expectOne(r => r.url === '/api/satellites');
    expect(request.request.params.get('q')).toBe(q);
    request.flush({ results, catalogFetchedAt: '2026-09-30T12:00:00Z' });
  }

  it('takes digits as a NORAD number without looking anything up', async () => {
    const { input, type, changed } = await create();
    expect(input.value).toBe('25544');
    type('48274');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http.expectNone('/api/satellites');
    expect(changed).toEqual([48274]);
  });

  it('debounces typing into one request and chooses with the keyboard', async () => {
    const { fixture, input, type, key, changed, picked } = await create();
    type('i'); type('is'); type('iss');
    // A name that is not chosen yet is not a satellite: the previous one must not stay.
    expect(changed).toEqual([null, null, null]);
    answer('iss', [ISS, OBJECT]);
    fixture.detectChanges();

    const options = fixture.nativeElement.querySelectorAll('[role="option"]');
    expect(options).toHaveLength(2);
    expect(input.getAttribute('aria-expanded')).toBe('true');
    expect(input.getAttribute('aria-activedescendant')).toBe(options[0].id);

    key('ArrowDown');
    const enter = key('Enter');
    expect(enter.defaultPrevented).toBe(true);
    expect(picked).toEqual([OBJECT]);
    expect(changed.at(-1)).toBe(49044);
    expect(input.value).toBe('ISS OBJECT XK');
    expect(input.getAttribute('aria-expanded')).toBe('false');
  });

  it('chooses with the mouse', async () => {
    const { fixture, type, changed } = await create();
    type('hubble telescope');
    answer('hubble telescope', [{ noradId: 20580, name: 'HST' }]);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('[role="option"]').dispatchEvent(new MouseEvent('mousedown'));
    expect(changed.at(-1)).toBe(20580);
  });

  it('says so when nothing matches, and when the search is unavailable', async () => {
    const { fixture, type } = await create();
    type('hubble');
    answer('hubble', []);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('HST for Hubble');

    type('zarya');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    http.expectOne(r => r.url === '/api/satellites').flush(
      { detail: 'The satellite name list could not be downloaded.' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('could not be downloaded');
  });

  it('in add mode, searches numbers too, emits only a choice, and clears for the next one', async () => {
    const { fixture, input, type, key, changed, picked } = await create('add');
    type('25544');
    answer('25544', [ISS]);
    fixture.detectChanges();
    key('Enter');
    expect(changed).toEqual([]);
    expect(picked).toEqual([ISS]);
    expect(input.value).toBe('');
  });

  it('never chooses a suggestion that belongs to earlier text', async () => {
    const { fixture, type, key, changed, picked } = await create();
    type('iss');
    answer('iss', [ISS]);
    fixture.detectChanges();

    type('hst');
    // Enter within the debounce: the ISS suggestion is gone, nothing is chosen.
    expect(key('Enter').defaultPrevented).toBe(false);
    expect(picked).toEqual([]);
    expect(changed.at(-1)).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('[role="option"]')).toHaveLength(0);
    answer('hst', [{ noradId: 20580, name: 'HST' }]);
  });

  it('ignores a response that lands after the text became a NORAD number', async () => {
    const { fixture, type, key, changed, picked } = await create();
    type('iss');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    const late = http.expectOne(r => r.url === '/api/satellites');
    type('48274');
    late.flush({ results: [ISS], catalogFetchedAt: '2026-09-30T12:00:00Z' });
    fixture.detectChanges();

    key('ArrowDown');
    key('Enter');
    expect(picked).toEqual([]);
    expect(changed.at(-1)).toBe(48274);
    expect(fixture.nativeElement.querySelectorAll('[role="option"]')).toHaveLength(0);
  });

  it('lets Enter submit the form when no suggestion is highlighted', async () => {
    const { key } = await create();
    expect(key('Enter').defaultPrevented).toBe(false);
  });
});
