import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PassDto, PassesResponse, PhaseDto, TrackPointDto } from '../api/passes.model';
import { formatDoppler } from '../format';
import { saveTrackCsv } from '../export/track-csv';
import { PassClock } from '../pass-viewer/pass-clock';
import { sampleAt } from '../pass-viewer/sky-geometry';
import { closestApproach, runsOf, symmetricBound, timeTicks } from './profile-geometry';

/** Drawing frame, in viewBox units. */
const WIDTH = 640;
const LEFT = 66;
const RIGHT = 14;
const PLOT = WIDTH - LEFT - RIGHT;
const ELEVATION = { top: 14, height: 118 };
const STRIP = { top: 142, height: 6 };
const SHIFT = { top: 166, height: 84 };
const HEIGHT = 282;

type Light = 'visible' | 'lit' | 'shade';

/**
 * The selected pass against time: how high, when it is worth looking, and how the
 * frequency slides.
 *
 * The sky chart answers "where"; this answers "when" and "how much". The elevation curve
 * says how long the satellite stays above the roofs. The strip beneath it says when it
 * is sunlit against a dark sky - the minutes worth being outside for. The lower panel is
 * the Doppler shift at the requested frequency, the S-curve a radio amateur tunes along;
 * without a frequency it is the range rate the shift is made from, so the panel is never
 * empty and never invented.
 *
 * It shares `PassClock` with the sky chart and the globe: the playhead is the same
 * instant in every view, and a click on the profile moves all three.
 */
@Component({
  selector: 'app-pass-profile',
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pass-profile.html',
  styleUrl: './pass-profile.scss',
})
export class PassProfile {
  readonly response = input.required<PassesResponse>();
  readonly pass = input.required<PassDto>();

  protected readonly clock = inject(PassClock);
  protected readonly frame = { width: WIDTH, height: HEIGHT, left: LEFT, right: WIDTH - RIGHT, elevation: ELEVATION, strip: STRIP, shift: SHIFT };
  protected readonly elevationGrid = [0, 30, 60, 90];

  protected readonly threshold = computed(() => this.response().minElevationDeg);
  protected readonly frequencyMhz = computed(() => this.response().frequencyMhz);
  protected readonly start = computed(() => Date.parse(this.pass().aos.instant));
  protected readonly end = computed(() => Date.parse(this.pass().los.instant));
  protected readonly track = computed<readonly PhaseDto[]>(() =>
    this.pass().track.length ? this.pass().track : [this.pass().aos, this.pass().culmination, this.pass().los]);

  /** Doppler only when every sample carries one; a half-drawn curve would be a lie. */
  protected readonly doppler = computed(() =>
    this.frequencyMhz() !== null && this.track().every(point => point.dopplerHz !== null));

  protected readonly shiftBound = computed(() => symmetricBound(this.track().map(point => this.shiftOf(point))));
  /** The bound is already a round figure, so the axis drops the decimals the readout keeps. */
  protected readonly shiftAxis = computed(() => {
    const bound = this.shiftBound();
    const label = !this.doppler() ? `${bound} km/s` : bound >= 1000 ? `${bound / 1000} kHz` : `${bound} Hz`;
    return { top: `+${label}`, bottom: `−${label}` };
  });

  protected readonly ticks = computed(() => timeTicks(this.start(), this.end(), 7).map(instant => ({ instant, x: this.x(instant) })));

  protected readonly elevationPath = computed(() => this.polyline(this.track(), point => this.elevationY(point.elevationDeg)));
  protected readonly elevationArea = computed(() => {
    const track = this.track();
    const base = ELEVATION.top + ELEVATION.height;
    return `${this.x(Date.parse(track[0].instant)).toFixed(1)},${base} ${this.elevationPath()} `
      + `${this.x(Date.parse(track[track.length - 1].instant)).toFixed(1)},${base}`;
  });
  protected readonly shiftPath = computed(() => this.polyline(this.track(), point => this.shiftY(this.shiftOf(point))));
  protected readonly thresholdY = computed(() => this.elevationY(this.threshold()));

  /** Sunlight and darkness along the pass; empty when the API sent no track. */
  protected readonly strip = computed(() => {
    const track = this.pass().track;
    if (!track.length) return [];
    return runsOf<TrackPointDto, Light>(track, point => point.visible ? 'visible' : point.illuminated ? 'lit' : 'shade')
      .map(run => {
        const from = this.x(Date.parse(run.points[0].instant));
        const to = this.x(Date.parse(run.points[run.points.length - 1].instant));
        return { kind: run.kind, x: from, width: Math.max(1, to - from) };
      });
  });

  protected readonly approach = computed(() => {
    const found = closestApproach(this.track());
    return found ? { ...found, x: this.x(found.instant) } : undefined;
  });

  protected readonly current = computed(() => sampleAt(this.track(), this.clock.instant())!);
  protected readonly playhead = computed(() => ({
    x: this.x(Date.parse(this.current().instant)),
    elevationY: this.elevationY(this.current().elevationDeg),
    shiftY: this.shiftY(this.shiftOf(this.current())),
  }));

  /** The two ends of the lower curve, in words: what the screen reader gets instead of the S. */
  protected readonly shiftSummary = computed(() => {
    const aos = this.pass().aos, los = this.pass().los;
    if (this.doppler()) {
      const rise = formatDoppler(aos.dopplerHz!), set = formatDoppler(los.dopplerHz!);
      return $localize`Doppler ${rise}:rise: at rise, ${set}:set: at set`;
    }
    const rise = this.signed(aos.rangeRateKmS), set = this.signed(los.rangeRateKmS);
    return $localize`Range rate ${rise}:rise: km/s at rise, ${set}:set: km/s at set`;
  });

  protected readonly chartLabel = computed(() => {
    const summary = this.shiftSummary();
    return this.doppler()
      ? $localize`Elevation and Doppler shift of the selected pass over time. ${summary}:summary:.`
      : $localize`Elevation and range rate of the selected pass over time. ${summary}:summary:.`;
  });

  protected readonly hasTrack = computed(() => this.pass().track.length > 0);

  protected shiftOf(point: PhaseDto): number {
    return this.doppler() ? point.dopplerHz ?? 0 : point.rangeRateKmS;
  }

  protected elevationY(elevationDeg: number): number {
    const clamped = Math.max(0, Math.min(90, elevationDeg));
    return ELEVATION.top + (1 - clamped / 90) * ELEVATION.height;
  }

  /** Moves every view of the pass to the instant under the pointer. */
  protected seek(event: MouseEvent): void {
    const svg = event.currentTarget as SVGElement;
    const box = svg.getBoundingClientRect();
    if (!(box.width > 0)) return;
    const x = (event.clientX - box.left) / box.width * WIDTH;
    this.clock.seek(this.start() + (x - LEFT) / PLOT * (this.end() - this.start()));
  }

  protected download(): void {
    saveTrackCsv(this.response(), this.pass());
  }

  private x(instant: number): number {
    const span = this.end() - this.start();
    return LEFT + (span > 0 ? (instant - this.start()) / span : 0) * PLOT;
  }

  private shiftY(value: number): number {
    const middle = SHIFT.top + SHIFT.height / 2;
    return middle - value / this.shiftBound() * (SHIFT.height / 2);
  }

  private polyline(points: readonly PhaseDto[], y: (point: PhaseDto) => number): string {
    return points.map(point => `${this.x(Date.parse(point.instant)).toFixed(1)},${y(point).toFixed(1)}`).join(' ');
  }

  private signed(value: number): string {
    return `${value > 0 ? '+' : value < 0 ? '−' : ''}${Math.abs(value).toFixed(2)}`;
  }
}
