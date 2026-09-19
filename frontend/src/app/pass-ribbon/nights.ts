import { PassDto } from '../api/passes.model';

/** One column of the ribbon: an observing night, and the passes it holds. */
export interface Night {
  /** Local date of the evening the night is named after, as YYYY-MM-DD. */
  readonly key: string;
  /** What the column header shows, e.g. "Tue 22". */
  readonly label: string;
  /** The two halves of the label, stacked in the ribbon: "Tue" over "22". */
  readonly weekday: string;
  readonly day: number;
  /** Spelled out for screen readers, e.g. "Tuesday 22 September". */
  readonly longLabel: string;
  readonly passes: readonly PassDto[];
}

/**
 * The evening a pass belongs to, as a local {@code Date} pinned at noon.
 *
 * <p>A night is named after its evening, the way observers name one: a pass at 02:00 on
 * the 23rd belongs to the night of the 22nd, not to a column of its own wedged between
 * two empty ones. Shifting the local time back twelve hours before taking the date does
 * exactly that, and it needs no special case at a month or year boundary.
 *
 * <p>Pinning the result at local noon rather than midnight is not cosmetic either: adding
 * a day to a midnight date lands on 23:00 or 01:00 when the clocks change, and the column
 * for that night would go missing or double. Noon has twelve hours of slack on both
 * sides.
 */
function eveningOf(instant: string): Date {
  const local = new Date(instant);
  local.setHours(local.getHours() - 12);
  local.setHours(12, 0, 0, 0);
  return local;
}

function keyOf(evening: Date): string {
  const month = String(evening.getMonth() + 1).padStart(2, '0');
  const day = String(evening.getDate()).padStart(2, '0');
  return `${evening.getFullYear()}-${month}-${day}`;
}

const SHORT = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric' });
const WEEKDAY = new Intl.DateTimeFormat(undefined, { weekday: 'short' });
const LONG = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long' });

/** Exposed for the sky chart and the tests; the ribbon itself only needs the grouping. */
export function nightKey(instant: string): string {
  return keyOf(eveningOf(instant));
}

/**
 * Groups the passes into consecutive nights, **empty ones included**.
 *
 * <p>A night without a pass is a result, not a gap to close up: it says the satellite
 * went by out of reach, or in daylight. Dropping it would make two nights a week apart
 * look adjacent, and the ribbon would stop being a calendar.
 */
export function groupIntoNights(passes: readonly PassDto[]): Night[] {
  if (passes.length === 0) {
    return [];
  }

  const buckets = new Map<string, PassDto[]>();
  let first = eveningOf(passes[0].aos.instant);
  let last = first;

  for (const pass of passes) {
    const evening = eveningOf(pass.aos.instant);
    const key = keyOf(evening);
    (buckets.get(key) ?? buckets.set(key, []).get(key)!).push(pass);
    if (evening < first) first = evening;
    if (evening > last) last = evening;
  }

  const nights: Night[] = [];
  for (const cursor = new Date(first); cursor <= last; cursor.setDate(cursor.getDate() + 1)) {
    const key = keyOf(cursor);
    nights.push({
      key,
      label: SHORT.format(cursor),
      weekday: WEEKDAY.format(cursor),
      day: cursor.getDate(),
      longLabel: LONG.format(cursor),
      passes: buckets.get(key) ?? [],
    });
  }
  return nights;
}
