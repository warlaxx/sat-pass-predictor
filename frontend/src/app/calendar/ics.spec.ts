import { describe, it, expect, vi, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from '../app';
import { THREE_LOADER } from '../globe/three-loader';
import { buildCalendar, calendarFileName, visibleWindow } from './ics';
import { PassesResponse, TrackPointDto } from '../api/passes.model';

/** Four samples 10 s apart; the middle two are favourable unless told otherwise. */
function prediction(noradId: number, minute: number, favourable = true): PassesResponse {
  const track: TrackPointDto[] = [0, 10, 20, 30].map((second, index) => ({
    instant: new Date(Date.UTC(2026, 8, 19, 20, minute, second)).toISOString(),
    azimuthDeg: 90, elevationDeg: index === 2 ? 45 : 10, rangeKm: 1000,
    subPoint: { latitudeDeg: 45, longitudeDeg: 5, altitudeKm: 420 },
    illuminated: true, visible: favourable && (index === 1 || index === 2),
  }));
  return {
    satellite: { noradId, name: `Satellite ${noradId}` },
    observer: { latitudeDeg: 45, longitudeDeg: 5, altitudeM: 170 },
    tle: { epoch: '2026-09-19T00:00:00Z', fetchedAt: '2026-09-19T19:00:00Z', ageSeconds: 72000, source: 'test', line1: '', line2: '' },
    computedAt: '2026-09-19T20:00:00Z', minElevationDeg: 10,
    passes: [{ aos: track[0], culmination: track[2], los: track[3], durationSeconds: 30, track }],
  };
}

/** Unfolds continuation lines, so assertions read properties rather than line breaks. */
function unfold(calendar: string): string[] {
  return calendar.replace(/\r\n /g, '').split('\r\n');
}

describe('visibleWindow', () => {
  it('spans the first run of favourable samples only', () => {
    const pass = prediction(25544, 1).passes[0];
    expect(visibleWindow(pass)).toEqual({ start: pass.track[1].instant, end: pass.track[2].instant });
    expect(visibleWindow(prediction(25544, 1, false).passes[0])).toBeUndefined();
  });
});

describe('buildCalendar', () => {
  it('writes one event per potentially visible pass, over its favourable interval', () => {
    const lines = unfold(buildCalendar(prediction(25544, 1))!);
    expect(lines[0]).toBe('BEGIN:VCALENDAR');
    expect(lines.at(-2)).toBe('END:VCALENDAR');
    expect(lines.filter(line => line === 'BEGIN:VEVENT')).toHaveLength(1);
    expect(lines).toContain('DTSTART:20260919T200110Z');
    expect(lines).toContain('DTEND:20260919T200120Z');
    expect(lines).toContain('DTSTAMP:20260919T200000Z');
    expect(lines).toContain('SUMMARY:Satellite 25544 - max 45° E');
    expect(lines).toContain('TRIGGER:-PT10M');
    expect(lines.find(line => line.startsWith('UID:'))).toBe('UID:25544-20260919T200100Z-45-5@sat-pass-predictor');
  });

  it('skips passes with no favourable sample, and has nothing to offer without one', () => {
    const visible = prediction(25544, 1);
    const hidden = prediction(25544, 30, false);
    const mixed: PassesResponse = { ...visible, passes: [...hidden.passes, ...visible.passes] };
    expect(unfold(buildCalendar(mixed)!).filter(line => line === 'BEGIN:VEVENT')).toHaveLength(1);
    expect(buildCalendar(hidden)).toBeUndefined();
  });

  it('gives a single favourable sample a minute rather than no duration', () => {
    const response = prediction(25544, 1);
    const track = response.passes[0].track.map((point, index) => ({ ...point, visible: index === 1 }));
    const lines = unfold(buildCalendar({ ...response, passes: [{ ...response.passes[0], track }] })!);
    expect(lines).toContain('DTSTART:20260919T200110Z');
    expect(lines).toContain('DTEND:20260919T200210Z');
  });

  it('escapes TEXT values and folds lines at 75 octets with CRLF', () => {
    const response = prediction(25544, 1);
    const named = { ...response, satellite: { ...response.satellite, name: 'A; B, C\\ D' } };
    const calendar = buildCalendar(named)!;
    expect(unfold(calendar)).toContain('SUMMARY:A\\; B\\, C\\\\ D - max 45° E');
    expect(calendar).not.toMatch(/[^\r]\n/);
    for (const line of calendar.split('\r\n')) {
      expect(new TextEncoder().encode(line).length).toBeLessThanOrEqual(75);
    }
    expect(unfold(calendar).find(line => line.startsWith('DESCRIPTION:Rise'))).toContain('\\n');
  });
});

describe('calendarFileName', () => {
  it('slugs the satellite name, and falls back to the NORAD number', () => {
    const response = prediction(25544, 1);
    expect(calendarFileName({ ...response, satellite: { noradId: 25544, name: 'ISS (ZARYA)' } })).toBe('iss-zarya-visible-passes.ics');
    expect(calendarFileName({ ...response, satellite: { noradId: 25544, name: '***' } })).toBe('25544-visible-passes.ics');
  });
});

describe('calendar export in the application', () => {
  afterEach(() => vi.restoreAllMocks());

  async function show(response: PassesResponse) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: THREE_LOADER, useValue: () => Promise.reject(new Error('WebGL unavailable in test')) },
    ] });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    fixture.nativeElement.querySelector('form.query').dispatchEvent(new Event('submit', { cancelable: true }));
    TestBed.tick();
    TestBed.inject(HttpTestingController).expectOne(request => request.url === '/api/passes').flush(response);
    await fixture.whenStable();
    return fixture.nativeElement.querySelector('button.calendar') as HTMLButtonElement;
  }

  it('downloads the visible passes on screen without another request', async () => {
    const created = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:calendar');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.download).toBe('satellite-25544-visible-passes.ics');
    });
    const button = await show(prediction(25544, 1));
    expect(button.disabled).toBe(false);
    button.click();
    expect(clicked).toHaveBeenCalledOnce();
    const blob = created.mock.calls[0][0] as Blob;
    expect(blob.type).toBe('text/calendar;charset=utf-8');
    expect(await blob.text()).toContain('BEGIN:VEVENT');
    TestBed.inject(HttpTestingController).verify();
  });

  it('is disabled when no pass of the window has a favourable sample', async () => {
    expect((await show(prediction(25544, 1, false))).disabled).toBe(true);
  });
});
