import { describe, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Globe } from './globe';
import { THREE_LOADER } from './three-loader';
import { ObserverDto, PassDto, TrackPointDto } from '../api/passes.model';

const point = (seconds: number, latitudeDeg: number, longitudeDeg: number): TrackPointDto => ({
  instant: new Date(Date.UTC(2026, 8, 19, 20, 0, seconds)).toISOString(),
  azimuthDeg: 0, elevationDeg: 20, rangeKm: 1000, illuminated: false,
  subPoint: { latitudeDeg, longitudeDeg, altitudeKm: 420 },
});
const track = [point(0, 45, 4), point(60, 46, 5), point(120, 47, 6)];
const pass: PassDto = { aos: track[0], culmination: track[1], los: track[2], durationSeconds: 120, track };
const observer: ObserverDto = { latitudeDeg: 45.7578, longitudeDeg: 4.832, altitudeM: 170 };

/**
 * The mandatory fallback (see ROADMAP milestone 8) is the one behaviour of this component
 * worth pinning in a test: three.js is loaded from a CDN, so "it failed to load" is a real
 * runtime state, not just a theoretical branch. Rendering the actual WebGL scene is left
 * to manual verification in the browser — jsdom has no GPU to check it against.
 */
describe('Globe', () => {
  it('shows an explicit message, and keeps the rest of the page usable, when three.js cannot load', async () => {
    await TestBed.configureTestingModule({
      imports: [Globe],
      providers: [
        provideZonelessChangeDetection(),
        { provide: THREE_LOADER, useValue: () => Promise.reject(new Error('offline')) },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(Globe);
    await fixture.autoDetectChanges();
    fixture.componentRef.setInput('pass', pass);
    fixture.componentRef.setInput('observer', observer);
    fixture.componentRef.setInput('threshold', 10);
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('could not load three.js');
    expect(fixture.nativeElement.querySelector('canvas')).not.toBeNull();
  });
});
