import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { LANGUAGES, SiteLanguage, currentLanguage, pathIn } from './locale';

/**
 * The public origin of the site, without a trailing slash: 'https://example.org'.
 *
 * Set at build time by scripts/build.mjs (`ng build --define`), from SITE_URL or from
 * Vercel's VERCEL_PROJECT_PRODUCTION_URL. Empty in development and in tests, and then
 * the canonical and alternate links are left out: a search engine ignores a relative
 * hreflang, and a canonical pointing at localhost would be worse than none.
 */
declare const ngSiteOrigin: string | undefined;
export const SITE_ORIGIN: string = typeof ngSiteOrigin === 'string' ? ngSiteOrigin.replace(/\/+$/, '') : '';

export const SITE_NAME = 'NextPass';

/**
 * The preview image of every page, one per language (public/og/): 1200×630, the size
 * Facebook, LinkedIn, X and messaging apps crop to. They require an absolute address,
 * so the tags need the public origin like the canonical link does.
 */
export const SHARE_IMAGE = { width: 1200, height: 630 } as const;

export interface PageMeta {
  /** The page's own title, without the site name. */
  readonly title?: string;
  readonly description?: string;
  /** True for a page that must stay out of search results: the not-found page. */
  readonly noindex?: boolean;
  /** Structured data for this page, as schema.org JSON-LD. */
  readonly jsonLd?: object;
}

/**
 * Everything in the head that a search engine or a link preview reads.
 *
 * Called on every navigation by the title strategy, and again by a page that learns a
 * better title once its data arrives (a satellite's name). Prerendering runs the same
 * code at build time, so the static HTML carries the tags before any script runs.
 */
@Injectable({ providedIn: 'root' })
export class Seo {
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly document = inject(DOCUMENT);
  private readonly language = currentLanguage();

  /** Applies a page's tags; `routerUrl` is the router's URL, without the language prefix. */
  apply(routerUrl: string, page: PageMeta): void {
    const title = page.title ? `${page.title} · ${SITE_NAME}` : SITE_NAME;
    this.title.setTitle(title);
    this.meta.updateTag({ property: 'og:title', content: page.title ?? SITE_NAME });
    this.meta.updateTag({ property: 'og:site_name', content: SITE_NAME });
    this.meta.updateTag({ property: 'og:type', content: 'website' });
    this.meta.updateTag({ property: 'og:locale', content: LANGUAGES[this.language].ogLocale });
    this.meta.updateTag({ property: 'og:locale:alternate', content: LANGUAGES[this.other()].ogLocale });
    this.shareImage();

    if (page.description) {
      this.meta.updateTag({ name: 'description', content: page.description });
      this.meta.updateTag({ property: 'og:description', content: page.description });
    }

    if (page.noindex) this.meta.updateTag({ name: 'robots', content: 'noindex' });
    else this.meta.removeTag('name="robots"');

    this.links(routerUrl, page.noindex ?? false);
    this.structuredData(page.jsonLd);
  }

  /** The schema.org description of the predictor, for the home page. */
  application(description: string): object {
    return {
      '@context': 'https://schema.org',
      '@type': 'WebApplication',
      name: SITE_NAME,
      description,
      applicationCategory: 'ScienceApplication',
      operatingSystem: 'Any',
      inLanguage: this.language,
      ...(SITE_ORIGIN ? { url: SITE_ORIGIN + pathIn(this.language, '/') } : {}),
      offers: { '@type': 'Offer', price: '0', priceCurrency: 'EUR' },
    };
  }

  /** A large preview when the image has an absolute address, the small card otherwise. */
  private shareImage(): void {
    if (!SITE_ORIGIN) {
      for (const selector of ['property="og:image"', 'property="og:image:width"', 'property="og:image:height"',
        'property="og:image:alt"', 'name="twitter:image"']) this.meta.removeTag(selector);
      this.meta.updateTag({ name: 'twitter:card', content: 'summary' });
      return;
    }
    const url = `${SITE_ORIGIN}/og/${this.language}.png`;
    const alt = $localize`:Alt text of the link preview image:NextPass: when the ISS passes over you tonight, on a sky chart`;
    this.meta.updateTag({ property: 'og:image', content: url });
    this.meta.updateTag({ property: 'og:image:width', content: String(SHARE_IMAGE.width) });
    this.meta.updateTag({ property: 'og:image:height', content: String(SHARE_IMAGE.height) });
    this.meta.updateTag({ property: 'og:image:alt', content: alt });
    this.meta.updateTag({ name: 'twitter:card', content: 'summary_large_image' });
    this.meta.updateTag({ name: 'twitter:image', content: url });
  }

  private other(): SiteLanguage {
    return this.language === 'fr' ? 'en' : 'fr';
  }

  /** Canonical and hreflang alternates; nothing without a known origin or on a noindex page. */
  private links(routerUrl: string, noindex: boolean): void {
    const head = this.document.head;
    head.querySelectorAll('link[rel="canonical"], link[rel="alternate"][hreflang]').forEach(link => link.remove());
    if (!SITE_ORIGIN || noindex) {
      this.meta.removeTag('property="og:url"');
      return;
    }
    const url = (language: SiteLanguage): string => SITE_ORIGIN + pathIn(language, routerUrl);
    this.link({ rel: 'canonical', href: url(this.language) });
    for (const language of Object.keys(LANGUAGES) as SiteLanguage[]) {
      this.link({ rel: 'alternate', hreflang: language, href: url(language) });
    }
    // English is the default for a reader whose language is neither.
    this.link({ rel: 'alternate', hreflang: 'x-default', href: url('en') });
    this.meta.updateTag({ property: 'og:url', content: url(this.language) });
  }

  private link(attributes: Record<string, string>): void {
    const link = this.document.createElement('link');
    for (const [name, value] of Object.entries(attributes)) link.setAttribute(name, value);
    this.document.head.appendChild(link);
  }

  private structuredData(data: object | undefined): void {
    this.document.head.querySelector('script#structured-data')?.remove();
    if (!data) return;
    const script = this.document.createElement('script');
    script.id = 'structured-data';
    script.type = 'application/ld+json';
    // '<' escaped so that no text in the data can close the script element.
    script.textContent = JSON.stringify(data).replace(/</g, '\\u003c');
    this.document.head.appendChild(script);
  }
}



/** "International Space Station: next passes and when to see it": the title of one satellite. */
export function satelliteTitle(name: string): string {
  return $localize`:Page title of one satellite:${name}:name:: next passes and when to see it`;
}

/**
 * The description of one satellite's page: its featured blurb and what the page offers,
 * or a sentence built on the catalogue name. Kept under the ~155 characters a result shows.
 */
export function satelliteDescription(name: string, blurb?: string): string {
  if (blurb) return `${blurb} ` + $localize`:Meta description, after a satellite's blurb:Next passes over your location, with rise, peak and set times.`;
  return $localize`:Meta description of one satellite:When ${name}:name: passes over you: next rise, peak and set times, visibility and orbit, from fresh CelesTrak elements.`;
}
