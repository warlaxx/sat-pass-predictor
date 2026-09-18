import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PassDto } from '../api/passes.model';
import { compassPoint, formatDuration } from '../format';
import { PassClock } from './pass-clock';
import { project, sampleAt } from './sky-geometry';

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
  protected readonly clock = inject(PassClock);
  protected readonly project = project;
  protected readonly compass = compassPoint;
  protected readonly duration = formatDuration;
  protected readonly start = computed(() => Date.parse(this.pass().aos.instant));
  protected readonly end = computed(() => Date.parse(this.pass().los.instant));
  protected readonly track = computed(() => this.pass().track.length ? this.pass().track : [this.pass().aos, this.pass().culmination, this.pass().los]);
  protected readonly current = computed(() => sampleAt(this.track(), this.clock.instant())!);
  protected readonly path = computed(() => this.track().map(point => {
    const { x, y } = project(point);
    return `${x},${y}`;
  }).join(' '));
  protected readonly phases = computed(() => [
    { name: 'Rise', point: this.pass().aos },
    { name: 'Culmination', point: this.pass().culmination },
    { name: 'Set', point: this.pass().los },
  ]);
  protected readonly ticks = computed(() => {
    const ticks = [];
    for (let instant = Math.ceil(this.start() / 60000) * 60000; instant <= this.end(); instant += 60000) {
      const point = sampleAt(this.track(), instant)!;
      ticks.push({ instant, ...project(point) });
    }
    return ticks;
  });

  constructor() {
    effect(onCleanup => {
      this.clock.reset(this.start(), this.end());
      onCleanup(() => this.clock.pause());
    });
  }

  protected scrub(event: Event): void {
    this.clock.seek(Number((event.target as HTMLInputElement).value));
  }
}
