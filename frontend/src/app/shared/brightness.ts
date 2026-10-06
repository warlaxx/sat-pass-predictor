import { PassDto } from '../api/passes.model';

/**
 * How bright a pass looks at its best, while it can be seen (ABD-35): the lowest
 * magnitude among its samples that are sunlit above a dark sky. Undefined when the API
 * gave no magnitude (any satellite but the ISS for now) or no sample is visible.
 */
export interface PassBrightness {
  readonly magnitude: number;
  readonly verdict: 'naked-eye' | 'binoculars' | 'too-faint';
}

/** A suburban sky shows stars to about +4; +3 leaves a margin for haze and city light. */
export const NAKED_EYE_LIMIT = 3;
/** Common 7×50 binoculars reach about +9 in a dark sky, +7 in a town. */
export const BINOCULARS_LIMIT = 7;

export function passBrightness(pass: PassDto): PassBrightness | undefined {
  let best: number | undefined;
  for (const point of pass.track) {
    if (point.visible && point.magnitude !== undefined && point.magnitude !== null) {
      best = best === undefined ? point.magnitude : Math.min(best, point.magnitude);
    }
  }
  if (best === undefined) return undefined;
  const verdict = best <= NAKED_EYE_LIMIT ? 'naked-eye' : best <= BINOCULARS_LIMIT ? 'binoculars' : 'too-faint';
  return { magnitude: best, verdict };
}

/** "−2.9": a real minus sign, one decimal, as magnitudes are written. */
export function formatMagnitude(magnitude: number): string {
  const text = Math.abs(magnitude).toFixed(1);
  return magnitude < 0 && text !== '0.0' ? `−${text}` : text;
}

export function verdictLabel(verdict: PassBrightness['verdict']): string {
  switch (verdict) {
    case 'naked-eye': return $localize`:Brightness of a pass:naked eye`;
    case 'binoculars': return $localize`:Brightness of a pass:binoculars`;
    case 'too-faint': return $localize`:Brightness of a pass:too faint to see`;
  }
}
