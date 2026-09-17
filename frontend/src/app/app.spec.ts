import { describe, beforeEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { App } from './app';

/**
 * The shell before anything has been asked of the backend.
 *
 * The HTTP layer is a double and no request is expected: the point of this test is that
 * the page is useful with an empty resource. A first render that fires a request nobody
 * asked for would show up here as an unexpected call.
 */
describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
  });

  it('should render the application title', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const heading = fixture.nativeElement.querySelector('h1') as HTMLElement;
    expect(heading.textContent).toContain('Sat Pass Predictor');
  });

  it('starts on the idle state rather than a spinner', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const state = fixture.nativeElement.querySelector('.state') as HTMLElement;
    expect(state.textContent).toContain('Pick a satellite');
  });

  it('offers the Lyon defaults, which are the ones the API applies', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const inputs = fixture.nativeElement.querySelectorAll('input') as NodeListOf<HTMLInputElement>;
    expect(inputs[0].value).toBe('25544');
    expect(inputs[1].value).toBe('45.7578');
  });
});
