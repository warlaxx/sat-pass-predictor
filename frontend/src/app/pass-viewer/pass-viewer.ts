import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PassDto } from '../api/passes.model';
import {
  aosUncertaintySeconds, compassPoint, describeElevation, elevationColour, formatAge,
  isRemarkable, utcOffsetLabel,
} from '../format';
import { PassClock } from './pass-clock';
import { project, sampleAt } from './sky-geometry';

interface Run {
  readonly points: string;
  readonly kind: 'lit' | 'shade' | 'neutral';
}

/**
 * The selected pass, in full: where to look (the sky chart), when (the three phases), how
 * good it is (the tiles), and a playhead that drives every picture of it at once.
 *
 * The globe is projected in rather than owned: it is a heavier view with its own
 * failure mode (three.js may not load), and the sky chart must never depend on it.
 */
@Component({
  selector: 'app-pass-viewer',
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pass-viewer.html',
  styleUrl: './pass-viewer.scss',
})
export class PassViewer {
  readonly pass = input.required<PassDto>();
  readonly threshold = input.required<number>();
  /** Age of the elements at the pass's rise, in seconds. Omitted, the tile is too. */
  readonly elementsAge = input<number | undefined>(undefined);

  protected readonly clock = inject(PassClock);
  protected readonly project = project;
  protected readonly compass = compassPoint;
  protected readonly rim = Array.from({ length: 12 }, (_, i) => i * 30);
  protected readonly start = computed(() => Date.parse(this.pass().aos.instant));
  protected readonly end = computed(() => Date.parse(this.pass().los.instant));
  protected readonly track = computed(() => this.pass().track.length ? this.pass().track : [this.pass().aos, this.pass().culmination, this.pass().los]);
  protected readonly current = computed(() => sampleAt(this.track(), this.clock.instant())!);
  protected readonly illumination = computed(() => this.pass().track.length > 0);
  protected readonly potentiallyVisible = computed(() => this.pass().track.some(point => point.visible));
  protected readonly zone = computed(() => utcOffsetLabel(this.pass().aos.instant));

  /** Split on satellite illumination; an entirely eclipsed pass is shaded too. */
  protected readonly runs = computed<Run[]>(() => {
    const track = this.track();
    const format = (point: (typeof track)[number]): string => {
      const { x, y } = project(point);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    };
    if (!this.illumination()) return [{ kind: 'neutral', points: track.map(format).join(' ') }];
    const points = this.pass().track;
    const runs: Run[] = [];
    let first = 0;
    for (let i = 1; i <= points.length; i++) {
      if (i === points.length || points[i].illuminated !== points[first].illuminated) {
        runs.push({
          kind: points[first].illuminated ? 'lit' : 'shade',
          points: points.slice(first, Math.min(i, points.length - 1) + 1).map(format).join(' '),
        });
        first = i;
      }
    }
    return runs;
  });

  protected readonly phases = computed(() => [
    { name: 'Rise', kind: 'rise', point: this.pass().aos },
    { name: 'Culmination', kind: 'peak', point: this.pass().culmination },
    { name: 'Set', kind: 'set', point: this.pass().los },
  ]);

  protected readonly ticks = computed(() => {
    const ticks = [];
    for (let instant = Math.ceil(this.start() / 60000) * 60000; instant <= this.end(); instant += 60000) {
      const point = sampleAt(this.track(), instant)!;
      ticks.push({ instant, ...project(point) });
    }
    return ticks;
  });

  /** Playhead and culmination as fractions of the pass, for the scrubber's CSS. */
  protected readonly progress = computed(() => this.fraction(this.clock.instant()));
  protected readonly peakAt = computed(() => this.fraction(Date.parse(this.pass().culmination.instant)));

  /** "6 min 44": the tile is big enough that the trailing "s" is noise. */
  protected readonly minutes = computed(() => {
    const whole = Math.round(this.pass().durationSeconds);
    return `${Math.floor(whole / 60)} min ${String(whole % 60).padStart(2, '0')}`;
  });

  protected readonly peakColour = computed(() => elevationColour(this.pass().culmination.elevationDeg));
  protected readonly peakWords = computed(() => {
    const elevation = this.pass().culmination.elevationDeg;
    return isRemarkable(elevation) ? `remarkable pass, ${describeElevation(elevation)}` : describeElevation(elevation);
  });

  constructor() {
    effect(onCleanup => {
      this.clock.reset(this.start(), this.end());
      onCleanup(() => this.clock.pause());
    });
  }

  protected sin(degrees: number): number {
    return Math.sin(degrees * Math.PI / 180);
  }

  protected cos(degrees: number): number {
    return Math.cos(degrees * Math.PI / 180);
  }

  protected ageLabel(seconds: number): string {
    return formatAge(seconds);
  }

  protected timing(seconds: number): string {
    const uncertainty = aosUncertaintySeconds(seconds);
    return uncertainty === 0 ? 'timing good to about a second' : `times good to about ±${uncertainty} s`;
  }

  protected scrub(event: Event): void {
    this.clock.seek(Number((event.target as HTMLInputElement).value));
  }

  private fraction(instant: number): number {
    const span = this.end() - this.start();
    return span > 0 ? Math.max(0, Math.min(1, (instant - this.start()) / span)) : 0;
  }
}
