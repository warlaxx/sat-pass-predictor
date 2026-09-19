import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Reveal } from './reveal';

@Component({ imports: [Reveal], template: '<section appReveal>Readable result</section>' })
class Host {}

describe('Reveal', () => {
  afterEach(() => { TestBed.resetTestingModule(); vi.unstubAllGlobals(); });

  async function setup(reduced = false) {
    let callback: IntersectionObserverCallback = () => {};
    const disconnect = vi.fn();
    const observe = vi.fn();
    vi.stubGlobal('matchMedia', () => ({ matches: reduced }));
    vi.stubGlobal('IntersectionObserver', class {
      constructor(cb: IntersectionObserverCallback) { callback = cb; }
      observe = observe;
      disconnect = disconnect;
    });
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    return { fixture, disconnect, observe, enter: (visible: boolean) => callback(
      [{ isIntersecting: visible } as IntersectionObserverEntry], {} as IntersectionObserver,
    ) };
  }

  it('reveals on entry and releases the observer', async () => {
    const { fixture, observe, disconnect, enter } = await setup();
    const section = fixture.nativeElement.querySelector('section') as HTMLElement;
    expect(observe).toHaveBeenCalledWith(section);
    enter(false);
    expect(section.classList.contains('reveal-enter')).toBe(false);
    enter(true);
    expect(section.classList.contains('reveal-enter')).toBe(true);
    expect(disconnect).toHaveBeenCalled();
  });

  it('keeps content visible and skips observing for reduced motion', async () => {
    const { fixture, observe } = await setup(true);
    expect(observe).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Readable result');
    expect(fixture.nativeElement.querySelector('.reveal-enter')).toBeNull();
  });

  it('disconnects when destroyed before entering the viewport', async () => {
    const { fixture, disconnect } = await setup();
    fixture.destroy();
    expect(disconnect).toHaveBeenCalled();
  });

  it('works when IntersectionObserver is unavailable', async () => {
    vi.stubGlobal('IntersectionObserver', undefined);
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Readable result');
  });
});
