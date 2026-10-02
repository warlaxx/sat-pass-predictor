import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { describe, it, expect } from 'vitest';
import { RouteTransition, pageLabel } from './route-transition';
import { PRICING_ENABLED } from '../shared/site';

describe('pageLabel', () => {
  it('numbers the header pages in their order', () => {
    expect(pageLabel('/')).toBe('01 — Predictor');
    expect(pageLabel('/separations')).toBe('03 — Separations');
    expect(pageLabel('/developers#quotas')).toBe('05 — Developers');
    expect(pageLabel('/status?x=1')).toBe(PRICING_ENABLED ? '08 — Status' : '07 — Status');
  });

  it('names the pages outside the header without a number', () => {
    expect(pageLabel('/satellites/25544')).toBe('Satellite');
    expect(pageLabel('/separations/S100685')).toBe('Separation');
    expect(pageLabel('/legal#privacy')).toBe('Legal');
    expect(pageLabel('/nowhere')).toBe('Page');
  });
});

describe('RouteTransition', () => {
  it('lets a navigation through at once when there is no curtain to draw', async () => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideRouter([])] });
    await expect(TestBed.inject(RouteTransition).cover('/methodology')).resolves.toBe(true);
  });
});
