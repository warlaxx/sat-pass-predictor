import { TestBed } from '@angular/core/testing';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { PassDto, TrackPointDto } from '../api/passes.model';
import { NextPass } from './next-pass';
import { nextPassState } from './next-pass-state';

const T0 = Date.UTC(2026, 8, 22, 19, 0);

/** A four-minute pass rising `minutes` after T0, favourable on its middle sample or not. */
function pass(minutes: number, visible: boolean): PassDto {
  const point = (offset: number, elevationDeg: number, favourable: boolean): TrackPointDto => ({
    instant: new Date(T0 + (minutes * 60 + offset) * 1000).toISOString(),
    azimuthDeg: 270 + offset / 4, elevationDeg, rangeKm: 800, rangeRateKmS: 0, dopplerHz: null,
    subPoint: { latitudeDeg: 45, longitudeDeg: 4, altitudeKm: 420 }, illuminated: favourable, visible: favourable,
  });
  const track = [point(0, 10, false), point(120, 60, visible), point(240, 10, false)];
  return { aos: track[0], culmination: track[1], los: track[2], durationSeconds: 240, track };
}

describe('nextPassState', () => {
  const passes = [pass(30, false), pass(120, false), pass(300, true)];

  it('counts down to the next rise, and to the next pass worth going out for', () => {
    const state = nextPassState(passes, T0);
    expect(state?.kind).toBe('next');
    if (state?.kind !== 'next') return;
    expect(state.pass).toBe(passes[0]);
    expect(state.waitMs).toBe(30 * 60_000);
    expect(state.visibleFrom).toBeUndefined();
    expect(state.nextVisible?.pass).toBe(passes[2]);
    expect(state.nextVisible?.waitMs).toBe((300 * 60 + 120) * 1000);
  });

  it('says a pass is in progress, with where to look right now', () => {
    const state = nextPassState(passes, T0 + (30 * 60 + 60) * 1000);
    expect(state?.kind).toBe('now');
    if (state?.kind !== 'now') return;
    expect(state.remainingMs).toBe(180_000);
    expect(state.position.elevationDeg).toBeCloseTo(35);
  });

  it('gives the favourable time of a next pass that is itself visible', () => {
    const state = nextPassState(passes, T0 + 200 * 60_000);
    expect(state?.kind === 'next' && state.visibleFrom).toBe(passes[2].track[1].instant);
  });

  it('knows when the whole window has set, and says nothing about an empty one', () => {
    expect(nextPassState(passes, T0 + 400 * 60_000)).toEqual({ kind: 'over', last: passes[2] });
    expect(nextPassState([], T0)).toBeUndefined();
  });
});

describe('NextPass', () => {
  afterEach(() => vi.useRealTimers());

  it('shows the countdown and hands the pass to the page', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(T0);
    const fixture = TestBed.createComponent(NextPass);
    const passes = [pass(30, false), pass(300, true)];
    fixture.componentRef.setInput('passes', passes);
    const selected: string[] = [];
    fixture.componentInstance.select.subscribe(instant => selected.push(instant));
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Next pass in 30 min 00 s');
    expect(element.textContent).toContain('not visible to the eye');
    expect(element.textContent).toContain('Next potentially visible pass');
    (element.querySelector('button.link') as HTMLButtonElement).click();
    expect(selected).toEqual([passes[1].aos.instant]);
    fixture.destroy();
  });
});
