import { ObjectRole, RecordedDate, RecordedOrbit } from '../../api/separations.model';

/**
 * How a recorded date reads, with exactly the precision it was recorded with and no
 * more: `2026 Sep?` is "September 2026", never "1 September 2026". Times are UTC, the
 * record's own time scale, and say so.
 */
export function recordedDateLabel(date: RecordedDate, locale: string): string {
  if (!date.at || !date.precision) return date.text;
  const at = new Date(date.at);
  const utc = { timeZone: 'UTC' } as const;
  switch (date.precision) {
    case 'DECADE':
      return `${at.getUTCFullYear()}s`;
    case 'YEAR':
      return String(at.getUTCFullYear());
    case 'QUARTER':
      return $localize`:Quarter of a year, as Q3 2026:Q${Math.floor(at.getUTCMonth() / 3) + 1}:quarter: ${at.getUTCFullYear()}:year:`;
    case 'MONTH':
      return new Intl.DateTimeFormat(locale, { ...utc, month: 'long', year: 'numeric' }).format(at);
    case 'DAY':
      return new Intl.DateTimeFormat(locale, { ...utc, day: 'numeric', month: 'long', year: 'numeric' }).format(at);
    case 'MINUTE':
    case 'SECOND':
      return new Intl.DateTimeFormat(locale, {
        ...utc, day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit',
        ...(date.precision === 'SECOND' ? { second: '2-digit' } : {}), hourCycle: 'h23',
      }).format(at) + ' UTC';
  }
}

/** A short date for a list row: the same precision, abbreviated month. */
export function recordedDateShort(date: RecordedDate, locale: string): string {
  if (!date.at || !date.precision) return date.text;
  const at = new Date(date.at);
  const utc = { timeZone: 'UTC' } as const;
  switch (date.precision) {
    case 'MONTH':
      return new Intl.DateTimeFormat(locale, { ...utc, month: 'short', year: 'numeric' }).format(at);
    case 'DAY':
      return new Intl.DateTimeFormat(locale, { ...utc, day: 'numeric', month: 'short', year: 'numeric' }).format(at);
    case 'MINUTE':
    case 'SECOND':
      return new Intl.DateTimeFormat(locale, {
        ...utc, day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
      }).format(at);
    default:
      return recordedDateLabel(date, locale);
  }
}

/** "to the month · uncertain": how much of the date is known. */
export function precisionLabel(date: RecordedDate): string {
  const precision = {
    DECADE: $localize`:Date precision:to the decade`,
    YEAR: $localize`:Date precision:to the year`,
    QUARTER: $localize`:Date precision:to the quarter`,
    MONTH: $localize`:Date precision:to the month`,
    DAY: $localize`:Date precision:to the day`,
    MINUTE: $localize`:Date precision:to the minute`,
    SECOND: $localize`:Date precision:to the second`,
  }[date.precision ?? 'DAY'];
  return date.uncertain ? `${precision} · ` + $localize`:Date precision:uncertain` : precision;
}

/**
 * Whole months from launch to separation, or days under two months; undefined when
 * either date is unknown or coarser than a month, where a count would be invented.
 */
export function timeSinceLaunch(launch: RecordedDate | null, separation: RecordedDate): string | undefined {
  const fine = (date: RecordedDate | null): date is RecordedDate & { at: string } =>
    !!date?.at && !!date.precision && ['MONTH', 'DAY', 'MINUTE', 'SECOND'].includes(date.precision);
  if (!fine(launch) || !fine(separation)) return undefined;
  const from = new Date(launch.at);
  const to = new Date(separation.at);
  const days = Math.round((to.getTime() - from.getTime()) / 86_400_000);
  if (days < 0) return undefined;
  if (separation.precision !== 'MONTH' && days < 60) {
    return $localize`:Time since launch:${days}:days: days`;
  }
  const months = (to.getUTCFullYear() - from.getUTCFullYear()) * 12 + to.getUTCMonth() - from.getUTCMonth();
  return $localize`:Time since launch:≈ ${months}:months: months`;
}

/** "382 × 394 km", or the orbit class alone when the record has no altitudes. */
export function orbitLabel(orbit: RecordedOrbit): string {
  const km = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 0 });
  if (orbit.perigeeKm === null) return orbit.orbitClass ?? '—';
  if (orbit.apogeeKm === null) return $localize`:Orbit of an object that left Earth orbit:Escaped Earth orbit`;
  return `${km.format(orbit.perigeeKm).replace(/,/g, ' ')} × ${km.format(orbit.apogeeKm).replace(/,/g, ' ')} km`;
}

/** Geostationary objects do not pass over: they hold one point in the sky. */
export function isGeostationary(orbit: RecordedOrbit): boolean {
  return (orbit.orbitClass ?? '').startsWith('GEO');
}

export function roleLabel(role: ObjectRole): string {
  return {
    payload: $localize`:Role of a catalogued object:Payload`,
    'rocket-stage': $localize`:Role of a catalogued object:Rocket stage`,
    component: $localize`:Role of a catalogued object:Component`,
    debris: $localize`:Role of a catalogued object:Debris`,
    other: $localize`:Role of a catalogued object:Object`,
  }[role];
}
