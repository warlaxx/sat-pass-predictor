import { describe, beforeEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

/**
 * The shell: the header, the footer and the outlet. Pages are drawn by the real routes,
 * so a link here that points nowhere shows up as the 404 page rather than a silent blank.
 */
describe('App shell', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideZonelessChangeDetection(), provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  async function at(url: string) {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    return fixture;
  }

  it('renders the brand as the heading of every page', async () => {
    const fixture = await at('/pricing');
    expect((fixture.nativeElement.querySelector('h1') as HTMLElement).textContent).toContain('Sat Pass Predictor');
  });

  it('opens on the predictor, idle, without a request nobody asked for', async () => {
    const fixture = await at('/');
    expect(fixture.nativeElement.querySelector('app-home .state').textContent).toContain('Pick a satellite');
    TestBed.inject(HttpTestingController).verify();
  });

  it('marks the current page in the site navigation', async () => {
    const fixture = await at('/developers');
    const current = fixture.nativeElement.querySelector('#site-nav a.current') as HTMLAnchorElement;
    expect(current.textContent).toContain('Developers');
    expect(current.getAttribute('aria-current')).toBe('page');
  });

  it('links every footer entry to a page that exists', async () => {
    const fixture = await at('/');
    // The language link leaves this build for the other one (/fr/), which no route of
    // this router serves: it has its own test below.
    const paths = [...fixture.nativeElement.querySelectorAll('footer a[href^="/"]:not([hreflang])')]
      .map((link) => new URL((link as HTMLAnchorElement).href).pathname);
    expect(paths.length).toBeGreaterThan(5);
    for (const path of new Set(paths)) {
      const page = await at(path);
      expect(page.nativeElement.querySelector('app-not-found'), path).toBeNull();
    }
  });

  it('links to the same page in French, as a full load of the other build', async () => {
    const fixture = await at('/legal#privacy');
    const links = [...fixture.nativeElement.querySelectorAll('a[hreflang="fr"]')] as HTMLAnchorElement[];
    expect(links.length).toBeGreaterThan(0);
    for (const link of links) {
      expect(link.getAttribute('href')).toBe('/fr/legal#privacy');
      expect(link.getAttribute('routerlink')).toBeNull();
    }
  });

  it('answers an unknown address with the not-found page', async () => {
    const fixture = await at('/no-such-page');
    expect(fixture.nativeElement.querySelector('app-not-found h2').textContent).toContain('never rose');
  });

  it('opens and closes the narrow-screen menu, and closes it on navigation', async () => {
    const fixture = await at('/');
    const toggle = fixture.nativeElement.querySelector('.menu-toggle') as HTMLButtonElement;
    toggle.click();
    await fixture.whenStable();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    await TestBed.inject(Router).navigateByUrl('/status');
    await fixture.whenStable();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });
});
