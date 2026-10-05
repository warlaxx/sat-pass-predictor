import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { describe, beforeEach, it, expect } from 'vitest';
import { PassesResponse, TrackPointDto } from '../api/passes.model';
import { DEFAULT_QUERY } from '../api/passes.query';
import { THREE_LOADER } from '../globe/three-loader';
import { Hero, shortDuration, tMinus } from './hero';
import { orbitRadius } from './hero-globe';

describe('hero formats', () => {
  it('counts down as T−HH:MM:SS, past a day in hours, never below zero', () => {
    expect(tMinus((2 * 3600 + 14 * 60 + 37) * 1000)).toBe('T−02:14:37');
    expect(tMinus(30 * 3600 * 1000)).toBe('T−30:00:00');
    expect(tMinus(-5000)).toBe('T−00:00:00');
  });

  it('writes a duration in minutes and seconds', () => {
    expect(shortDuration(408)).toBe('6 m 48 s');
    expect(shortDuration(59.6)).toBe('1 m 00 s');
  });

  it('draws low orbits to scale and caps high ones inside the frame', () => {
    expect(orbitRadius(420)).toBeCloseTo(1.066, 3);
    expect(orbitRadius(35_786)).toBe(1.6);
  });
});

describe('Hero', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: THREE_LOADER, useValue: () => Promise.reject(new Error('WebGL unavailable in test')) },
      ],
    });
  });

  it('ticks towards nothing before the first search, and says so', async () => {
    const fixture = TestBed.createComponent(Hero);
    fixture.componentRef.setInput('query', DEFAULT_QUERY);
    await fixture.whenStable();
    const card = fixture.nativeElement.querySelector('.card') as HTMLElement;
    expect(card.textContent).toContain('ISS (ZARYA) over Lyon');
    expect(card.textContent).toContain('NOT YET COMPUTED');
    expect(card.querySelector('.countdown')!.textContent).toContain('T−––:––:––');
  });

  it('says in words what it is waiting for while the page computes, not dashes alone', async () => {
    const fixture = TestBed.createComponent(Hero);
    fixture.componentRef.setInput('query', DEFAULT_QUERY);
    fixture.componentRef.setInput('pending', true);
    await fixture.whenStable();
    const card = fixture.nativeElement.querySelector('.card') as HTMLElement;
    expect(card.textContent).toContain('COMPUTING');
    expect(card.querySelector('.note')!.textContent).toContain('Computed live as the page opens');
    expect(card.querySelector('.locate')!.textContent).toContain('Use my position');
  });

  it('counts down to the next computed pass and reads the orbit off the elements', async () => {
    const rise = Date.now() + 3_600_000;
    const point = (offset: number, elevationDeg: number): TrackPointDto => ({
      instant: new Date(rise + offset * 1000).toISOString(), azimuthDeg: 315 + offset / 10, elevationDeg,
      rangeKm: 800, rangeRateKmS: 0, dopplerHz: null,
      subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 }, illuminated: true, visible: true,
    });
    const track = [point(0, 10), point(200, 67.4), point(408, 10)];
    const response: PassesResponse = {
      satellite: { noradId: 25544, name: 'ISS (ZARYA)' },
      tle: {
        epoch: new Date().toISOString(), ageSeconds: 3600, source: 'CelesTrak', fetchedAt: new Date().toISOString(),
        line1: '1 25544U 98067A   26273.50000000  .00016717  00000-0  10270-3 0  9005',
        line2: '2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.50375746 20001',
      },
      observer: { latitudeDeg: 12.5, longitudeDeg: -3.25, altitudeM: 200 },
      minElevationDeg: 10, frequencyMhz: null, computedAt: new Date().toISOString(),
      passes: [{ aos: track[0], culmination: track[1], los: track[2], durationSeconds: 408, track }],
    };

    const fixture = TestBed.createComponent(Hero);
    fixture.componentRef.setInput('query', DEFAULT_QUERY);
    fixture.componentRef.setInput('response', response);
    await fixture.whenStable();
    const card = fixture.nativeElement.querySelector('.card') as HTMLElement;
    expect(card.textContent).toContain('over 12.50° N, 3.25° W');
    expect(card.textContent).toContain('VISIBLE');
    expect(card.querySelector('.countdown')!.textContent).toMatch(/T−0[01]:\d\d:\d\d/);
    expect(card.textContent).toContain('67.4°');
    expect(card.textContent).toContain('6 m 48 s');
    const hud = fixture.nativeElement.querySelector('.hud.top') as HTMLElement;
    expect(hud.textContent).toContain('INC 51.64°');
    expect(hud.textContent).toMatch(/ALT 4\d\d km · 7\.6\d km\/s/);
  });

  it('puts the observer where the form is, not where the last result was computed', async () => {
    const fixture = TestBed.createComponent(Hero);
    fixture.componentRef.setInput('query', { ...DEFAULT_QUERY, lat: 48.8566, lon: 2.3522, alt: 35 });
    fixture.componentRef.setInput('response', {
      satellite: { noradId: 25544, name: 'ISS (ZARYA)' },
      tle: { epoch: '', ageSeconds: 0, source: 'CelesTrak', fetchedAt: '', line1: '', line2: '' },
      observer: { latitudeDeg: 12.5, longitudeDeg: -3.25, altitudeM: 200 },
      minElevationDeg: 10, frequencyMhz: null, computedAt: new Date().toISOString(), passes: [],
    } satisfies PassesResponse);
    await fixture.whenStable();
    const readout = fixture.nativeElement.querySelector('.hud.observer') as HTMLElement;
    expect(readout.textContent).toContain('48.8566° N · 2.3522° E · 35 m');
    expect(readout.textContent).toContain('48.86° N, 2.35° E');
  });
});
