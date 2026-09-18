import { afterEach, describe, expect, it, vi } from 'vitest';
import { PassClock } from './pass-clock';

describe('pass clock', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('starts paused, scrubs, stops at LOS and cancels on reset', () => {
    let frame: FrameRequestCallback = () => {};
    const cancel = vi.fn();
    vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => { frame = callback; return 1; }));
    vi.stubGlobal('cancelAnimationFrame', cancel);
    const clock = new PassClock();
    clock.reset(1000, 3000);
    expect(clock.playing()).toBe(false);
    clock.toggle(); frame(0); frame(50);
    expect(clock.instant()).toBe(2000);
    frame(100);
    expect(clock.instant()).toBe(3000);
    expect(clock.playing()).toBe(false);
    clock.toggle();
    expect(clock.instant()).toBe(1000);
    clock.seek(2500);
    expect(clock.playing()).toBe(false);
    expect(clock.instant()).toBe(2500);
    clock.toggle(); clock.reset(4000, 6000);
    expect(cancel).toHaveBeenCalled();
    expect(clock.instant()).toBe(4000);
    clock.ngOnDestroy();
  });
  it('pauses when reduced motion is enabled and removes the listener', () => {
    let change = () => {};
    const media = { matches: false, addEventListener: (_: string, cb: () => void) => { change = cb; }, removeEventListener: vi.fn() };
    vi.stubGlobal('matchMedia', () => media);
    vi.stubGlobal('requestAnimationFrame', vi.fn(() => 1));
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
    const clock = new PassClock(); clock.reset(0, 1000); clock.toggle();
    media.matches = true; change();
    expect(clock.playing()).toBe(false);
    clock.ngOnDestroy(); expect(media.removeEventListener).toHaveBeenCalled();
  });
});
