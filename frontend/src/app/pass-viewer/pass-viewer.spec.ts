import { TestBed } from '@angular/core/testing';
import { describe, it, expect } from 'vitest';
import { PassViewer } from './pass-viewer';
import { PassClock } from './pass-clock';
import { PassDto, TrackPointDto } from '../api/passes.model';

const point = (seconds: number, azimuthDeg: number, elevationDeg: number): TrackPointDto => ({
  instant: new Date(Date.UTC(2026, 8, 19, 20, 0, seconds)).toISOString(), azimuthDeg, elevationDeg, rangeKm: 1000,
  illuminated: false, visible: false, subPoint: { latitudeDeg: 45, longitudeDeg: 4, altitudeKm: 420 },
});
const track = [point(0, 270, 20), point(60, 0, 70), point(120, 90, 20)];
const pass: PassDto = { aos: track[0], culmination: track[1], los: track[2], durationSeconds: 120, track };

describe('PassViewer', () => {
  it('renders the requested threshold and resets playback when the selection changes', async () => {
    const fixture = TestBed.createComponent(PassViewer);
    fixture.componentRef.setInput('pass', pass); fixture.componentRef.setInput('threshold', 20);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.threshold').getAttribute('r')).toBe('112');
    expect(fixture.nativeElement.querySelectorAll('.phase')).toHaveLength(3);
    expect(fixture.nativeElement.querySelectorAll('.trajectory.shade')).toHaveLength(1);
    expect(fixture.nativeElement.querySelectorAll('.trajectory.neutral')).toHaveLength(0);
    expect(fixture.nativeElement.textContent).toContain('Potential visibility requires');
    const slider = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    slider.value = String(Date.parse(track[1].instant)); slider.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(TestBed.inject(PassClock).instant()).toBe(Date.parse(track[1].instant));
    const next = { ...pass, aos: point(180, 90, 20), los: point(240, 180, 20) };
    fixture.componentRef.setInput('pass', next); await fixture.whenStable();
    expect(TestBed.inject(PassClock).instant()).toBe(Date.parse(next.aos.instant));
    fixture.destroy(); expect(TestBed.inject(PassClock).playing()).toBe(false);
  });
  it('does not confuse a sunlit daytime pass with potential visibility', async () => {
    const fixture = TestBed.createComponent(PassViewer);
    const lit = track.map(point => ({ ...point, illuminated: true, visible: false }));
    fixture.componentRef.setInput('pass', { ...pass, track: lit });
    fixture.componentRef.setInput('threshold', 10);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelectorAll('.trajectory.lit')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('No favourable visibility sample');
    fixture.componentRef.setInput('pass', {
      ...pass, track: lit.map((point, i) => ({ ...point, visible: i === 1 })),
    });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Potentially visible during part');
  });

});
