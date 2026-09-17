import { describe, beforeEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TleBanner } from './tle-banner';
import { TleDto } from '../api/passes.model';

function tleAged(days: number): TleDto {
  return {
    epoch: '2026-09-15T04:12:33Z',
    ageSeconds: Math.round(days * 86_400),
    source: 'celestrak',
    fetchedAt: '2026-09-16T11:25:04Z',
    line1: '1 25544U 98067A   26258.17538194  .00016717  00000-0  10270-3 0  9000',
    line2: '2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537',
  };
}

async function render(days: number) {
  const fixture = TestBed.createComponent(TleBanner);
  fixture.componentRef.setInput('tle', tleAged(days));
  await fixture.whenStable();
  return fixture;
}

describe('TleBanner', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TleBanner],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();
  });

  it('states the age of the elements in days', async () => {
    const fixture = await render(2.5);
    expect(fixture.nativeElement.textContent).toContain('2 d 12 h');
  });

  /**
   * The drift is shown as a range on purpose. A single figure would read as a
   * measurement; what is on display is an order of magnitude that depends on solar
   * activity nobody here is reading.
   */
  it('shows the drift as a range, never as one figure', async () => {
    const fixture = await render(4);
    expect(fixture.nativeElement.textContent).toContain('4 to 12 km');
  });

  it('turns to the alert colour past three days, well before the server refuses', async () => {
    expect((await render(2)).nativeElement.querySelector('.stale')).toBeNull();
    expect((await render(4)).nativeElement.querySelector('.stale')).not.toBeNull();
  });
});
