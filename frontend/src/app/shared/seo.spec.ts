import { describe, beforeEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { DOCUMENT, provideZonelessChangeDetection } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { SITE_ORIGIN, Seo, satelliteDescription } from './seo';
import { FEATURED } from './featured';

describe('Seo', () => {
  let seo: Seo;
  let document: Document;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    seo = TestBed.inject(Seo);
    document = TestBed.inject(DOCUMENT);
  });

  it('titles the page after the site and describes it for previews too', () => {
    seo.apply('/pricing', { title: 'Plans and quotas', description: 'Quotas of the API.' });
    expect(TestBed.inject(Title).getTitle()).toBe('Plans and quotas · NextPass');
    const meta = TestBed.inject(Meta);
    expect(meta.getTag('name="description"')!.content).toBe('Quotas of the API.');
    expect(meta.getTag('property="og:description"')!.content).toBe('Quotas of the API.');
    expect(meta.getTag('property="og:locale"')!.content).toBe('en_GB');
  });

  it('writes no canonical link while the public origin is unknown, rather than a wrong one', () => {
    expect(SITE_ORIGIN).toBe('');
    seo.apply('/satellites', { title: 'Satellites' });
    expect(document.head.querySelector('link[rel="canonical"]')).toBeNull();
    expect(document.head.querySelector('link[hreflang]')).toBeNull();
  });

  it('promises no preview image it cannot address, rather than a relative one', () => {
    seo.apply('/', { title: 'Home' });
    const meta = TestBed.inject(Meta);
    expect(meta.getTag('property="og:image"')).toBeNull();
    expect(meta.getTag('name="twitter:card"')!.content).toBe('summary');
  });

  it('keeps the not-found page out of search results, and lifts that on the next page', () => {
    const meta = TestBed.inject(Meta);
    seo.apply('/nowhere', { title: 'Page not found', noindex: true });
    expect(meta.getTag('name="robots"')!.content).toBe('noindex');
    seo.apply('/', { title: 'Home' });
    expect(meta.getTag('name="robots"')).toBeNull();
  });

  it('replaces the structured data of the previous page, and escapes it', () => {
    seo.apply('/', { jsonLd: { name: '</script><b>' } });
    seo.apply('/', { jsonLd: seo.application('Pass predictions.') });
    const scripts = document.head.querySelectorAll('script[type="application/ld+json"]');
    expect(scripts).toHaveLength(1);
    expect(JSON.parse(scripts[0].textContent!)['@type']).toBe('WebApplication');
    seo.apply('/', { jsonLd: { name: '</script>' } });
    expect(document.head.querySelector('script#structured-data')!.textContent).not.toContain('</script>');
  });

  it('keeps every featured satellite description within what a result shows', () => {
    for (const satellite of FEATURED.flatMap((group) => group.satellites)) {
      expect(satelliteDescription(satellite.name, satellite.blurb).length, satellite.name).toBeLessThanOrEqual(160);
    }
  });
});
