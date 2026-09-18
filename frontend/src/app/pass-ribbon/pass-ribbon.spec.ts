import { describe, beforeEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { PassRibbon } from './pass-ribbon';
import { PassDto } from '../api/passes.model';

function pass(localIso: string, peakDeg: number): PassDto {
  const instant = new Date(localIso).toISOString();
  const phase = { instant, azimuthDeg: 292.5, elevationDeg: 10, rangeKm: 1553 };
  return {
    aos: phase,
    culmination: { ...phase, azimuthDeg: 22.5, elevationDeg: peakDeg, rangeKm: 463 },
    los: { ...phase, azimuthDeg: 112.4 },
    durationSeconds: 404,
    track: [],
  };
}

const PASSES = [
  pass('2026-09-22T21:18:00', 63),
  pass('2026-09-23T02:05:00', 24),
  pass('2026-09-25T20:40:00', 81),
];

async function render(passes: readonly PassDto[], selected?: string) {
  const fixture = TestBed.createComponent(PassRibbon);
  fixture.componentRef.setInput('passes', passes);
  fixture.componentRef.setInput('selected', selected);
  await fixture.whenStable();
  return fixture;
}

describe('PassRibbon', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PassRibbon],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();
  });

  /**
   * Four columns for three passes over two nights: the two empty nights in between are
   * the whole point. Without them the 22nd and the 25th would sit side by side and the
   * ribbon would stop being a calendar.
   */
  it('draws one column per night, empty nights included', async () => {
    const fixture = await render(PASSES);

    expect(fixture.nativeElement.querySelectorAll('.night')).toHaveLength(4);
    expect(fixture.nativeElement.querySelectorAll('.none')).toHaveLength(2);
  });

  it('groups the after-midnight pass with the evening before it', async () => {
    const fixture = await render(PASSES);
    const columns = fixture.nativeElement.querySelectorAll('.night');

    expect(columns[0].querySelectorAll('button')).toHaveLength(2);
    expect(columns[3].querySelectorAll('button')).toHaveLength(1);
  });

  /**
   * Real buttons, not clickable divs: this is the one view a pointer is genuinely faster
   * at, which is exactly why it must also work without one.
   */
  it('makes every bar a button that says what it is', async () => {
    const fixture = await render(PASSES);
    const first = fixture.nativeElement.querySelector('button') as HTMLButtonElement;

    expect(first.tagName).toBe('BUTTON');
    const label = first.getAttribute('aria-label') ?? '';
    expect(label).toContain('63 degrees');
    expect(label).toContain('NNE');
    expect(label).toContain('6 min 44 s');
  });

  it('marks the selected pass with aria-pressed', async () => {
    const fixture = await render(PASSES, PASSES[2].aos.instant);
    const pressed = fixture.nativeElement.querySelectorAll('button[aria-pressed="true"]');

    expect(pressed).toHaveLength(1);
    expect(pressed[0].getAttribute('aria-label')).toContain('81 degrees');
  });

  it('emits the pass a click selects', async () => {
    const fixture = await render(PASSES);
    let emitted: string | undefined;
    fixture.componentInstance.select.subscribe((instant) => (emitted = instant));

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(emitted).toBe(PASSES[0].aos.instant);
  });

  /**
   * The scale is absolute - a share of 90 degrees - not relative to the best pass of the
   * window. Scaling to the tallest would make a mediocre evening look excellent whenever
   * the window holds nothing better, which is the opposite of what the ribbon is for.
   */
  it('scales the bars against 90 degrees, not against the best pass', async () => {
    const fixture = await render(PASSES);
    const bars = [...fixture.nativeElement.querySelectorAll('button')] as HTMLElement[];

    expect(bars[0].style.height).toBe('70%');
    expect(bars[1].style.height).toBe(`${(24 / 90) * 100}%`);
  });

  it('draws nothing at all for an empty window', async () => {
    const fixture = await render([]);

    expect(fixture.nativeElement.querySelectorAll('.night')).toHaveLength(0);
  });
});
