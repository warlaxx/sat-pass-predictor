import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { PassTable } from './pass-table';
import { PassDto, TrackPointDto } from '../api/passes.model';

describe('pass visibility labels', () => {
  it.each([false, true])('uses observer visibility, not sunlight alone (visible=%s)', async visible => {
    const point: TrackPointDto = {
      instant: '2026-09-19T18:00:00Z', azimuthDeg: 0, elevationDeg: 30, rangeKm: 800,
      subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 },
      illuminated: true, visible,
    };
    const pass: PassDto = { aos: point, culmination: point, los: point, durationSeconds: 120, track: [point] };
    const fixture = TestBed.createComponent(PassTable);
    fixture.componentRef.setInput('passes', [pass]);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(visible ? 'potentially visible' : 'no favourable sample');
  });
});
