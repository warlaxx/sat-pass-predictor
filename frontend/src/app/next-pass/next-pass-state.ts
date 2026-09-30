import { PassDto, PhaseDto } from '../api/passes.model';
import { visibleWindow } from '../calendar/ics';
import { sampleAt } from '../pass-viewer/sky-geometry';

/**
 * Where "now" falls among the passes of a response.
 *
 * The window starts when the server computed it, and a page left open keeps its answer:
 * an hour later the first row of the table may already have set. This is the one piece
 * of the interface that reads the browser's clock against the passes - only to count
 * down, never to move the satellite.
 */
export type NextPassState =
  /** A pass is above the threshold right now. */
  | { readonly kind: 'now'; readonly pass: PassDto; readonly remainingMs: number; readonly position: PhaseDto }
  /** The next pass to rise, and the next one worth going out for when that is another. */
  | {
      readonly kind: 'next';
      readonly pass: PassDto;
      readonly waitMs: number;
      readonly visibleFrom?: string;
      readonly nextVisible?: { readonly pass: PassDto; readonly start: string; readonly waitMs: number };
    }
  /** Every pass of the window has set. */
  | { readonly kind: 'over'; readonly last: PassDto };

export function nextPassState(passes: readonly PassDto[], now: number): NextPassState | undefined {
  if (passes.length === 0) return undefined;
  const index = passes.findIndex(pass => Date.parse(pass.los.instant) > now);
  if (index < 0) return { kind: 'over', last: passes[passes.length - 1] };

  const pass = passes[index];
  const rise = Date.parse(pass.aos.instant);
  if (rise <= now) {
    const track = pass.track.length ? pass.track : [pass.aos, pass.culmination, pass.los];
    return { kind: 'now', pass, remainingMs: Date.parse(pass.los.instant) - now, position: sampleAt(track, now)! };
  }

  const window = visibleWindow(pass);
  if (window) return { kind: 'next', pass, waitMs: rise - now, visibleFrom: window.start };
  for (const later of passes.slice(index + 1)) {
    const laterWindow = visibleWindow(later);
    if (laterWindow) {
      return {
        kind: 'next', pass, waitMs: rise - now,
        nextVisible: { pass: later, start: laterWindow.start, waitMs: Date.parse(laterWindow.start) - now },
      };
    }
  }
  return { kind: 'next', pass, waitMs: rise - now };
}
