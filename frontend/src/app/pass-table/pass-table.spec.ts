import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { PassTable } from './pass-table';
import { PassDto, TrackPointDto } from '../api/passes.model';

describe('pass visibility labels', () => {
  it.each([false, true])('uses observer visibility, not sunlight alone (visible=%s)', async visible => {
    const point: TrackPointDto = {
      instant: '2026-09-19T18:00:00Z', azimuthDeg: 0, elevationDeg: 30, rangeKm: 800, rangeRateKmS: 0, dopplerHz: null,
      subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 },
      illuminated: true, visible,
    };
    const pass: PassDto = { aos: point, culmination: point, los: point, durationSeconds: 120, track: [point] };
    const fixture = TestBed.createComponent(PassTable);
    fixture.componentRef.setInput('passes', [pass]);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(visible ? 'potentially visible' : 'no favourable sample');
  });

  /** ABD-35: with a magnitude, the row says how bright and what it takes to see it. */
  it('shows the brightest visible magnitude and its verdict', async () => {
    const point: TrackPointDto = {
      instant: '2026-09-19T18:00:00Z', azimuthDeg: 0, elevationDeg: 60, rangeKm: 450, rangeRateKmS: 0, dopplerHz: null,
      subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 },
      illuminated: true, visible: true, magnitude: -3.2,
    };
    const pass: PassDto = { aos: point, culmination: point, los: point, durationSeconds: 300, track: [point] };
    const fixture = TestBed.createComponent(PassTable);
    fixture.componentRef.setInput('passes', [pass]);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('mag −3.2');
    expect(fixture.nativeElement.textContent).toContain('naked eye');
  });
});

/** ABD-36: given where it is seen from, each row says how cloudy the sky should be at the peak. */
describe('pass cloud cover', () => {
  const point: TrackPointDto = {
    instant: '2026-10-07T21:12:00Z', azimuthDeg: 0, elevationDeg: 60, rangeKm: 450, rangeRateKmS: 0, dopplerHz: null,
    subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 },
    illuminated: true, visible: true,
  };
  const pass: PassDto = { aos: point, culmination: point, los: point, durationSeconds: 300, track: [point] };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(PassTable);
    fixture.componentRef.setInput('passes', [pass]);
    fixture.componentRef.setInput('observer', { latitudeDeg: 45.7578, longitudeDeg: 4.832, altitudeM: 170 });
    TestBed.tick();
    await Promise.resolve();
    return { fixture, request: TestBed.inject(HttpTestingController).expectOne('/api/weather/clouds?lat=45.8&lon=4.8') };
  }

  it('shows the forecast at the peak, and credits MET Norway', async () => {
    const { fixture, request } = await render();
    request.flush({
      latitudeDeg: 45.8, longitudeDeg: 4.8, updatedAt: '2026-10-07T09:21:33Z',
      hours: [{ time: '2026-10-07T21:00:00Z', cloudPercent: 12, stepHours: 1 }],
    });
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('clear sky · 12%');
    expect(fixture.nativeElement.querySelector('.credit').textContent).toContain('MET Norway');
  });

  it('shows the passes without a sky when the forecast is unavailable', async () => {
    const { fixture, request } = await render();
    request.flush({ title: 'Weather unavailable' }, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('potentially visible');
    expect(fixture.nativeElement.textContent).not.toContain('clear sky');
    expect(fixture.nativeElement.querySelector('.credit')).toBeNull();
  });
});
