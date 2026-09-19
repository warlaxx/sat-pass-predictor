import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { ObserverDto, PassDto } from '../api/passes.model';
import { compassPoint, hasIllumination } from '../format';
import { PassClock } from '../pass-viewer/pass-clock';
import { sampleAt } from '../pass-viewer/sky-geometry';
import {
  nearestTurn, panoramaFrame, skyline, starField, sunPosition, toScreen, unwrapAzimuths,
} from './panorama-geometry';

interface Run {
  readonly points: string;
  readonly kind: 'lit' | 'shade' | 'neutral';
}

/**
 * The pass as it will look from the garden: the horizon across the bottom, the arc it
 * draws in the sky above it, the minutes along the way.
 *
 * It is the first thing on the page because it is the one picture that needs no
 * explanation. It is also purely decorative for assistive technology - `aria-hidden` -
 * because it says nothing the sky chart, the phases and the table do not already say in
 * words.
 */
@Component({
  selector: 'app-sky-panorama',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let f = frame();
    <svg [attr.viewBox]="'0 0 ' + f.width + ' ' + f.height" [attr.height]="f.height" width="100%" aria-hidden="true">
      <defs>
        <linearGradient id="pano-sky" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#000000" />
          <stop offset="62%" stop-color="#030a1c" />
          <stop offset="100%" stop-color="#0b1a3a" />
        </linearGradient>
        <radialGradient id="pano-twilight">
          <stop offset="0%" stop-color="#6a4a2a" stop-opacity="0.6" />
          <stop offset="45%" stop-color="#2a2238" stop-opacity="0.28" />
          <stop offset="100%" stop-color="#000" stop-opacity="0" />
        </radialGradient>
        <filter id="pano-glow" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="5" result="blur" />
          <feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
        </filter>
      </defs>

      <rect [attr.width]="f.width" [attr.height]="f.height" fill="#000" />
      <rect [attr.width]="f.width" [attr.height]="f.horizonY + 2" fill="url(#pano-sky)" />
      @if (twilight(); as glow) {
        <ellipse [attr.cx]="glow.x" [attr.cy]="f.horizonY" [attr.rx]="glow.r" [attr.ry]="glow.r * 0.55"
                 fill="url(#pano-twilight)" [attr.opacity]="glow.opacity" />
      }
      @for (star of stars(); track $index) {
        <circle [attr.cx]="star.x" [attr.cy]="star.y" [attr.r]="star.r" fill="#fff" [attr.fill-opacity]="star.opacity" />
      }

      @for (line of gridlines(); track line.deg) {
        <line x1="0" [attr.x2]="f.width" [attr.y1]="line.y" [attr.y2]="line.y" stroke="#fff"
              [attr.stroke-opacity]="line.threshold ? 0.28 : 0.16" [attr.stroke-dasharray]="line.threshold ? '4 6' : '1 7'" />
        <text class="grid-label" [attr.x]="f.width - 12" [attr.y]="line.y - 6" text-anchor="end">
          {{ line.deg }}°{{ line.threshold ? ' threshold' : '' }}
        </text>
      }

      @for (run of runs(); track $index) {
        <polyline [attr.points]="run.points" [class]="'track ' + run.kind"
                  [attr.filter]="run.kind === 'shade' ? null : 'url(#pano-glow)'" />
      }
      @for (tick of ticks(); track tick.instant) {
        <circle [attr.cx]="tick.x" [attr.cy]="tick.y" r="2.4" fill="#fff" />
        <text class="time" [attr.x]="tick.x" [attr.y]="tick.y - 12" text-anchor="middle">{{ tick.instant | date: 'HH:mm' }}</text>
      }
      @if (satellite(); as sat) {
        <circle [attr.cx]="sat.x" [attr.cy]="sat.y" r="11" fill="none" stroke="#fff" stroke-opacity="0.45" />
        <circle [attr.cx]="sat.x" [attr.cy]="sat.y" r="5" fill="#fff" />
      }

      <polygon [attr.points]="ground()" fill="#000" />
      <line x1="0" [attr.x2]="f.width" [attr.y1]="f.horizonY + 12" [attr.y2]="f.horizonY + 12" stroke="#1b1d22" />
      @for (mark of compass(); track mark.az) {
        <line [attr.x1]="mark.x" [attr.x2]="mark.x" [attr.y1]="f.horizonY + 6" [attr.y2]="f.horizonY + 12" stroke="#4a4f5c" />
        <text class="cardinal" [class.north]="mark.north" [attr.x]="mark.x" [attr.y]="f.horizonY + 30" text-anchor="middle">{{ mark.label }}</text>
      }
    </svg>
  `,
  styles: `
    :host { display: block; }
    svg { display: block; }
    .grid-label { fill: var(--ink-3); font: 11px var(--font-mono); }
    .time { fill: var(--ink-2); font: 12px var(--font-mono); paint-order: stroke; stroke: #030a1c; stroke-width: 3px; }
    .cardinal { fill: var(--ink-3); font: 700 12px var(--font-display); }
    .cardinal.north { fill: var(--ink); }
    .track { fill: none; stroke-linecap: round; stroke-linejoin: round; }
    .track.lit { stroke: var(--lit); stroke-width: 3.2; }
    .track.neutral { stroke: var(--accent); stroke-width: 3.2; }
    .track.shade { stroke: var(--shade); stroke-opacity: 0.8; stroke-width: 2; stroke-dasharray: 6 7; }
  `,
})
export class SkyPanorama {
  readonly pass = input.required<PassDto>();
  readonly observer = input.required<ObserverDto>();
  readonly threshold = input.required<number>();

  private readonly clock = inject(PassClock);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly width = signal(1200);

  private readonly track = computed(() => {
    const pass = this.pass();
    return pass.track.length ? pass.track : [pass.aos, pass.culmination, pass.los];
  });

  protected readonly frame = computed(() => {
    const width = this.width();
    const height = Math.round(Math.min(520, Math.max(250, width * 0.36)));
    return panoramaFrame(this.track(), width, height);
  });

  protected readonly stars = computed(() => starField(this.frame().width, this.frame().horizonY));
  protected readonly ground = computed(() => skyline(this.frame().width, this.frame().horizonY));

  protected readonly gridlines = computed(() => {
    const f = this.frame();
    const threshold = this.threshold();
    const lines = [{ deg: threshold, threshold: true }];
    for (const deg of [30, 60]) {
      if (Math.abs(deg - threshold) > 5) lines.push({ deg, threshold: false });
    }
    return lines
      .map(line => ({ ...line, y: f.horizonY - line.deg * f.elevationScale }))
      .filter(line => line.y > 18);
  });

  /**
   * Split where the API says the satellite changes light. Until milestone 10 there is no
   * such change, and the whole arc is one neutral run: amber means "sunlit" on this page,
   * and a colour that means something is not spent on a guess.
   */
  protected readonly runs = computed<Run[]>(() => {
    const f = this.frame();
    const track = this.pass().track;
    if (!track.length) return [];
    const azimuths = unwrapAzimuths(track);
    const screen = track.map((point, i) => toScreen(f, azimuths[i], point.elevationDeg));
    const format = (i: number): string => `${screen[i].x.toFixed(1)},${screen[i].y.toFixed(1)}`;
    if (!hasIllumination(track)) {
      return [{ kind: 'neutral', points: screen.map((_, i) => format(i)).join(' ') }];
    }
    const runs: Run[] = [];
    let start = 0;
    for (let i = 1; i <= track.length; i++) {
      if (i === track.length || track[i].illuminated !== track[start].illuminated) {
        const end = Math.min(i, track.length - 1);
        const points = [];
        for (let j = start; j <= end; j++) points.push(format(j));
        runs.push({ kind: track[start].illuminated ? 'lit' : 'shade', points: points.join(' ') });
        start = i;
      }
    }
    return runs;
  });

  protected readonly ticks = computed(() => {
    const f = this.frame();
    const track = this.track();
    const start = Date.parse(this.pass().aos.instant);
    const end = Date.parse(this.pass().los.instant);
    const ticks = [];
    for (let instant = Math.ceil(start / 60000) * 60000; instant <= end; instant += 60000) {
      const point = sampleAt(track, instant)!;
      ticks.push({ instant, ...toScreen(f, nearestTurn(f, point.azimuthDeg), point.elevationDeg) });
    }
    return ticks;
  });

  protected readonly satellite = computed(() => {
    const f = this.frame();
    const instant = this.clock.instant();
    const start = Date.parse(this.pass().aos.instant);
    const end = Date.parse(this.pass().los.instant);
    if (instant < start || instant > end) return undefined;
    const point = sampleAt(this.track(), instant);
    return point && toScreen(f, nearestTurn(f, point.azimuthDeg), point.elevationDeg);
  });

  protected readonly compass = computed(() => {
    const f = this.frame();
    // Sixteen points when there is room for three letters between them, eight otherwise.
    const step = 22.5 * f.azimuthScale >= 40 ? 22.5 : 45;
    const halfSpan = f.width / 2 / f.azimuthScale;
    const marks = [];
    for (let az = Math.ceil((f.centreAz - halfSpan) / step) * step; az <= f.centreAz + halfSpan; az += step) {
      const normalised = ((az % 360) + 360) % 360;
      marks.push({
        az,
        x: toScreen(f, az, 0).x,
        label: compassPoint(normalised),
        north: normalised === 0,
      });
    }
    return marks;
  });

  /**
   * The glow of the Sun below the horizon, where it really is at culmination. Drawn only
   * during twilight: in full night there is no glow, and in daylight no pass is visible
   * anyway - the panorama is not the place to argue either.
   */
  protected readonly twilight = computed(() => {
    const f = this.frame();
    const sun = sunPosition(this.observer(), Date.parse(this.pass().culmination.instant));
    if (sun.elevationDeg > 6 || sun.elevationDeg < -18) return undefined;
    const r = Math.max(f.width * 0.3, 160);
    const x = toScreen(f, nearestTurn(f, sun.azimuthDeg), 0).x;
    if (x < -r || x > f.width + r) return undefined;
    const opacity = Math.max(0.15, 1 - Math.abs(sun.elevationDeg + 4) / 14);
    return { x, r, opacity };
  });

  constructor() {
    afterNextRender(() => {
      const element = this.host.nativeElement;
      const measure = (): void => {
        const width = Math.round(element.clientWidth);
        if (width > 0) this.width.set(width);
      };
      measure();
      if (typeof ResizeObserver === 'undefined') return;
      const observer = new ResizeObserver(measure);
      observer.observe(element);
      this.destroyRef.onDestroy(() => observer.disconnect());
    });
  }
}
