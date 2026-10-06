import { PassDto, PassesResponse } from '../api/passes.model';
import { compassPoint } from '../format';
import { saveText, slug } from '../shared/download';

/**
 * The potentially visible passes of a response, as an iCalendar (RFC 5545) file.
 *
 * Built in the browser from the response already on screen: no second request, no quota
 * spent, and the file says exactly what the page says. Only passes with at least one
 * favourable sample are exported - a calendar full of daylight passes nobody can see
 * would train its reader to ignore the reminders.
 *
 * Each event spans the favourable interval, not AOS to LOS: that is the time to be
 * outside looking up. Rise, peak and set stay in the description for the observer who
 * wants the whole arc. Every instant is written in UTC (`Z`); the calendar application
 * shows it in the reader's zone, the same rule the page follows.
 */

export interface VisibleWindow {
  readonly start: string;
  readonly end: string;
}

/** First consecutive run of favourable samples of a pass, or undefined when there is none. */
export function visibleWindow(pass: PassDto): VisibleWindow | undefined {
  const first = pass.track.findIndex(point => point.visible);
  if (first < 0) return undefined;
  let last = first;
  while (last + 1 < pass.track.length && pass.track[last + 1].visible) last++;
  return { start: pass.track[first].instant, end: pass.track[last].instant };
}

/** Minutes of warning before the favourable interval opens. */
const REMINDER_MINUTES = 10;

export function buildCalendar(response: PassesResponse): string | undefined {
  const events = response.passes.flatMap(pass => {
    const window = visibleWindow(pass);
    return window ? [event(response, pass, window)] : [];
  });
  if (events.length === 0) return undefined;
  return [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//NextPass//Visible passes//EN',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    `X-WR-CALNAME:${text(`${response.satellite.name} - visible passes`)}`,
    ...events.flat(),
    'END:VCALENDAR',
  ].map(fold).join('\r\n') + '\r\n';
}

/** "iss-zarya-visible-passes.ics" - something a downloads folder can hold several of. */
export function calendarFileName(response: PassesResponse): string {
  return `${slug(response.satellite.name) || response.satellite.noradId}-visible-passes.ics`;
}

/**
 * Hands the calendar of a response to the browser as a download; false when it has no
 * potentially visible pass, so there is nothing to save.
 */
export function saveCalendar(response: PassesResponse): boolean {
  const calendar = buildCalendar(response);
  if (!calendar) return false;
  saveText(calendar, 'text/calendar;charset=utf-8', calendarFileName(response));
  return true;
}

function event(response: PassesResponse, pass: PassDto, window: VisibleWindow): string[] {
  const { satellite, observer, tle } = response;
  const peak = pass.culmination;
  // A zero-length event is dropped or drawn as nothing by several calendar
  // applications; a lone favourable sample still deserves a slot of its own.
  const end = window.end === window.start
    ? new Date(Date.parse(window.start) + 60_000).toISOString()
    : window.end;
  const elevation = Math.round(peak.elevationDeg);
  const description = [
    $localize`Rise ${stamp(pass.aos.instant)}:time: UTC towards ${compassPoint(pass.aos.azimuthDeg)}:direction:`,
    $localize`Peak ${stamp(peak.instant)}:time: UTC, ${elevation}:elevation:° towards ${compassPoint(peak.azimuthDeg)}:direction:`,
    $localize`Set ${stamp(pass.los.instant)}:time: UTC towards ${compassPoint(pass.los.azimuthDeg)}:direction:`,
    $localize`Elements epoch ${stamp(tle.epoch)}:time: UTC (${tle.source}:source:). Times drift as the elements age.`,
    $localize`Potentially visible: sunlit satellite, Sun at least 6° below the horizon. Weather is not modelled.`,
  ].join('\n');
  return [
    'BEGIN:VEVENT',
    // Stable across downloads of the same prediction, so re-importing updates rather
    // than duplicates. The AOS is the identity of a pass everywhere else in the app.
    `UID:${satellite.noradId}-${basic(pass.aos.instant)}-${observer.latitudeDeg}-${observer.longitudeDeg}@nextpass.space`,
    `DTSTAMP:${basic(response.computedAt)}`,
    `DTSTART:${basic(window.start)}`,
    `DTEND:${basic(end)}`,
    `SUMMARY:${text($localize`${satellite.name}:satellite: · max ${elevation}:elevation:° ${compassPoint(peak.azimuthDeg)}:direction:`)}`,
    `DESCRIPTION:${text(description)}`,
    `GEO:${observer.latitudeDeg};${observer.longitudeDeg}`,
    'TRANSP:TRANSPARENT',
    'BEGIN:VALARM',
    'ACTION:DISPLAY',
    `DESCRIPTION:${text($localize`${satellite.name}:satellite: visible in ${REMINDER_MINUTES}:minutes: min`)}`,
    `TRIGGER:-PT${REMINDER_MINUTES}M`,
    'END:VALARM',
    'END:VEVENT',
  ];
}

/** 2026-09-22T19:48:31.123Z -> 20260922T194831Z. Sub-second digits have no place in a calendar. */
function basic(instant: string): string {
  return new Date(instant).toISOString().replace(/\.\d{3}/, '').replace(/[-:]/g, '');
}

/** 2026-09-22T19:48:31Z -> "22 Sep 19:48:31". */
function stamp(instant: string): string {
  const date = new Date(instant);
  const month = date.toLocaleString($localize.locale ?? 'en', { month: 'short', timeZone: 'UTC' });
  return `${date.getUTCDate()} ${month} ${date.toISOString().slice(11, 19)}`;
}

/** RFC 5545 §3.3.11: backslash, semicolon, comma and newline are escaped in TEXT. */
function text(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n');
}

const encoder = new TextEncoder();

/**
 * RFC 5545 §3.1: lines longer than 75 octets are folded with CRLF and a space.
 * Octets, not characters - the degree sign is two of them - and never inside a
 * multi-byte character.
 */
function fold(line: string): string {
  const parts: string[] = [];
  let current = '';
  let octets = 0;
  for (const char of line) {
    const size = encoder.encode(char).length;
    // Continuation lines start with a space, which counts towards their 75.
    const limit = parts.length === 0 ? 75 : 74;
    if (octets + size > limit) {
      parts.push(current);
      current = '';
      octets = 0;
    }
    current += char;
    octets += size;
  }
  parts.push(current);
  return parts.join('\r\n ');
}
