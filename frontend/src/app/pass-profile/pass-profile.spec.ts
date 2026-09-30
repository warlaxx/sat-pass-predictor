import { TestBed } from '@angular/core/testing';
import { describe, it, expect } from 'vitest';
import { PassProfile } from './pass-profile';
import { PassClock } from '../pass-viewer/pass-clock';
import { PassDto, PassesResponse, TrackPointDto } from '../api/passes.model';

const point = (seconds: number, elevationDeg: number, rangeRateKmS: number, dopplerHz: number | null,
  light: { illuminated: boolean; visible: boolean }): TrackPointDto => ({
  instant: new Date(Date.UTC(2026, 8, 22, 19, 0, seconds)).toISOString(),
  azimuthDeg: 300, elevationDeg, rangeKm: 1000 - elevationDeg * 5, rangeRateKmS, dopplerHz,
  subPoint: { latitudeDeg: 45, longitudeDeg: 4, altitudeKm: 420 }, ...light,
});

function responseWith(frequencyMhz: number | null): { response: PassesResponse; pass: PassDto } {
  const doppler = (rate: number) => frequencyMhz === null ? null : -rate / 299_792.458 * frequencyMhz * 1e6;
  const dark = { illuminated: false, visible: false };
  const seen = { illuminated: true, visible: true };
  const track = [
    point(0, 10, -6, doppler(-6), seen), point(60, 60, -1, doppler(-1), seen),
    point(120, 55, 3, doppler(3), dark), point(180, 10, 6, doppler(6), dark),
  ];
  const pass: PassDto = { aos: track[0], culmination: track[1], los: track[3], durationSeconds: 180, track };
  const response = {
    satellite: { noradId: 25544, name: 'ISS (ZARYA)' }, minElevationDeg: 10, frequencyMhz, passes: [pass],
  } as unknown as PassesResponse;
  return { response, pass };
}

async function render(frequencyMhz: number | null) {
  const { response, pass } = responseWith(frequencyMhz);
  const fixture = TestBed.createComponent(PassProfile);
  fixture.componentRef.setInput('response', response);
  fixture.componentRef.setInput('pass', pass);
  TestBed.inject(PassClock).reset(Date.parse(pass.aos.instant), Date.parse(pass.los.instant));
  await fixture.whenStable();
  return fixture;
}

describe('PassProfile', () => {
  it('draws the Doppler shift when a frequency was requested, and its closest approach', async () => {
    const fixture = await render(145.8);
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Doppler shift at 145.800 MHz');
    expect(element.textContent).toContain('Doppler +2.92 kHz at rise, −2.92 kHz at set');
    // Local time, like every clock on the page: 19:01:15 UTC in whatever zone the test runs.
    const local = new Date(Date.UTC(2026, 8, 22, 19, 1, 15)).toTimeString().slice(0, 8);
    expect(element.textContent).toContain(`closest approach ${local}`);
    expect(element.querySelectorAll('.light.visible')).toHaveLength(1);
    expect(element.querySelectorAll('.light.shade')).toHaveLength(1);
  });

  it('falls back to the range rate, and says how to get the shift in hertz', async () => {
    const element = (await render(null)).nativeElement as HTMLElement;
    expect(element.textContent).toContain('Range rate −6.00 km/s at rise, +6.00 km/s at set');
    expect(element.textContent).toContain('Give a downlink frequency');
  });

  it('moves the shared clock to the instant under the pointer', async () => {
    const fixture = await render(null);
    const svg = fixture.nativeElement.querySelector('svg') as SVGElement;
    svg.getBoundingClientRect = () => ({ left: 0, width: 640 }) as DOMRect;
    // Halfway along the plot: 66 + (640 - 66 - 14) / 2.
    svg.dispatchEvent(new MouseEvent('click', { clientX: 346 }));
    expect(TestBed.inject(PassClock).instant()).toBe(Date.UTC(2026, 8, 22, 19, 1, 30));
  });
});
