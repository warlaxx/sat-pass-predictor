import { PhaseDto } from '../api/passes.model';

/**
 * The arithmetic of the pass profile, kept out of the component so it can be tested.
 * Nothing here propagates an orbit: it reads the samples the API published.
 */

/** Candidate spacings between time labels, in minutes: round numbers a reader expects. */
const TICK_STEPS_MINUTES = [1, 2, 5, 10, 15, 30, 60, 120, 180, 360, 720, 1440];

/**
 * Round instants inside [start, end] to label the time axis, at most `maxTicks` of them.
 *
 * The step adapts to the pass: a four-minute ISS pass gets a label every minute, a
 * geostationary satellite "passing" for the whole two-day window gets one every six
 * hours instead of 2,880 overlapping ones.
 */
export function timeTicks(start: number, end: number, maxTicks = 8): number[] {
  const span = end - start;
  if (!(span > 0)) return [];
  const step = (TICK_STEPS_MINUTES.find(minutes => span / (minutes * 60_000) <= maxTicks)
    ?? TICK_STEPS_MINUTES[TICK_STEPS_MINUTES.length - 1]) * 60_000;
  const ticks: number[] = [];
  for (let instant = Math.ceil(start / step) * step; instant <= end; instant += step) ticks.push(instant);
  return ticks;
}

/**
 * The instant the range stops shrinking - the closest approach, where the Doppler shift
 * crosses zero - interpolated between the two samples that straddle it.
 *
 * Undefined when the range rate never changes sign inside the pass (a pass cut by the
 * window, or a satellite that only recedes), rather than a made-up crossing at an edge.
 */
export function closestApproach(
  track: readonly Pick<PhaseDto, 'instant' | 'rangeKm' | 'rangeRateKmS'>[],
): { readonly instant: number; readonly rangeKm: number } | undefined {
  for (let i = 1; i < track.length; i++) {
    const a = track[i - 1], b = track[i];
    if (a.rangeRateKmS <= 0 && b.rangeRateKmS > 0) {
      const span = b.rangeRateKmS - a.rangeRateKmS;
      const fraction = span > 0 ? -a.rangeRateKmS / span : 0;
      const start = Date.parse(a.instant), end = Date.parse(b.instant);
      return {
        instant: start + (end - start) * fraction,
        rangeKm: Math.min(a.rangeKm, b.rangeKm, a.rangeKm + (b.rangeKm - a.rangeKm) * fraction),
      };
    }
  }
  return undefined;
}

/**
 * A symmetric bound for a signed series: the largest magnitude, rounded up to a figure
 * with one or two significant digits so the axis label reads "±4 kHz", not "±3.4217 kHz".
 */
export function symmetricBound(values: readonly number[]): number {
  const largest = values.reduce((max, value) => Math.max(max, Math.abs(value)), 0);
  if (!(largest > 0)) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(largest));
  const steps = [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
  const bound = steps.find(step => step * magnitude >= largest)! * magnitude;
  // 1.5 × 0.1 is 0.15000000000000002 in binary; an axis label must not say so.
  return Number(bound.toPrecision(3));
}

/** Consecutive samples sharing a kind, each run ending on the first sample of the next. */
export function runsOf<T, K>(points: readonly T[], kindOf: (point: T) => K): { kind: K; points: T[] }[] {
  const runs: { kind: K; points: T[] }[] = [];
  let first = 0;
  for (let i = 1; i <= points.length; i++) {
    if (i === points.length || kindOf(points[i]) !== kindOf(points[first])) {
      runs.push({ kind: kindOf(points[first]), points: points.slice(first, Math.min(i, points.length - 1) + 1) });
      first = i;
    }
  }
  return runs;
}
