import { Injectable, OnDestroy, signal } from '@angular/core';

/** Shared playback time for the sky chart and the future globe. No background loop when paused. */
@Injectable({ providedIn: 'root' })
export class PassClock implements OnDestroy {
  readonly instant = signal(0);
  readonly playing = signal(false);
  private start = 0;
  private end = 0;
  private frame: number | undefined;
  private previous: number | undefined;
  private readonly motion = typeof matchMedia === 'function'
    ? matchMedia('(prefers-reduced-motion: reduce)') : undefined;
  private readonly motionChanged = () => { if (this.motion?.matches) this.pause(); };

  constructor() { this.motion?.addEventListener('change', this.motionChanged); }

  reset(start: number, end: number): void {
    this.pause();
    this.start = start;
    this.end = end;
    this.instant.set(start);
  }

  seek(instant: number): void {
    this.pause();
    this.instant.set(Math.max(this.start, Math.min(this.end, instant)));
  }

  toggle(): void {
    if (this.playing()) { this.pause(); return; }
    if (this.end <= this.start) return;
    if (this.instant() >= this.end) this.instant.set(this.start);
    this.playing.set(true);
    this.frame = requestAnimationFrame(this.tick);
  }

  pause(): void {
    if (this.frame !== undefined) cancelAnimationFrame(this.frame);
    this.frame = undefined;
    this.previous = undefined;
    this.playing.set(false);
  }

  private readonly tick = (now: number): void => {
    // Explicit playback at 20× real time; resuming a hidden tab cannot skip the pass.
    const elapsed = this.previous === undefined ? 0 : Math.min(now - this.previous, 100);
    this.previous = now;
    this.instant.update(value => Math.min(this.end, value + elapsed * 20));
    if (this.instant() >= this.end) { this.pause(); return; }
    this.frame = requestAnimationFrame(this.tick);
  };

  ngOnDestroy(): void {
    this.pause();
    this.motion?.removeEventListener('change', this.motionChanged);
  }
}
