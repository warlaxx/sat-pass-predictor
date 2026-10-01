/**
 * The files a crawler reads before any page: robots.txt and sitemap.xml.
 *
 * Pure functions, so node --test can check them without a build (seo-files.test.mjs).
 * build.mjs calls them once the prerendered pages exist.
 */

/** The languages of the site and their path prefixes, as in src/app/shared/locale.ts. */
export const LANGUAGES = { en: '', fr: '/fr' };

/**
 * The public origin the canonical links and the sitemap name: SITE_URL when set, else
 * the production domain Vercel exposes to every build, else none.
 */
export function siteOrigin(env) {
  const explicit = (env.SITE_URL ?? '').trim();
  if (explicit) return explicit.replace(/\/+$/, '');
  const vercel = (env.VERCEL_PROJECT_PRODUCTION_URL ?? '').trim();
  return vercel ? `https://${vercel.replace(/\/+$/, '')}` : '';
}

/** '/fr/satellites' -> { language: 'fr', path: '/satellites' }; '/fr' -> the French home. */
export function splitLanguage(route) {
  for (const [language, prefix] of Object.entries(LANGUAGES)) {
    if (prefix && (route === prefix || route.startsWith(`${prefix}/`))) {
      return { language, path: route.slice(prefix.length) || '/' };
    }
  }
  return { language: 'en', path: route || '/' };
}

/** The address of a page in a language, as the app's canonical links write it. */
export function pageUrl(origin, language, path) {
  const prefix = LANGUAGES[language];
  if (path === '/') return `${origin}${prefix ? `${prefix}/` : '/'}`;
  return `${origin}${prefix}${path}`;
}

const escapeXml = (text) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/**
 * One <url> per prerendered page and language, each listing every language it exists in
 * (and English as x-default), which is how Google pairs translations in a sitemap.
 */
export function sitemapXml(origin, routes) {
  const pages = new Map();
  for (const route of routes) {
    const { language, path } = splitLanguage(route);
    if (!pages.has(path)) pages.set(path, new Set());
    pages.get(path).add(language);
  }
  const entries = [];
  for (const [path, languages] of [...pages].sort(([a], [b]) => a.localeCompare(b))) {
    const alternates = [...languages].sort().map((language) =>
      `    <xhtml:link rel="alternate" hreflang="${language}" href="${escapeXml(pageUrl(origin, language, path))}"/>`);
    if (languages.has('en')) {
      alternates.push(`    <xhtml:link rel="alternate" hreflang="x-default" href="${escapeXml(pageUrl(origin, 'en', path))}"/>`);
    }
    for (const language of [...languages].sort()) {
      entries.push(['  <url>', `    <loc>${escapeXml(pageUrl(origin, language, path))}</loc>`, ...alternates, '  </url>'].join('\n'));
    }
  }
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">',
    ...entries,
    '</urlset>',
    '',
  ].join('\n');
}

/** Everything may be crawled; the sitemap is announced only when its address is known. */
export function robotsTxt(origin) {
  return ['User-agent: *', 'Allow: /', ...(origin ? ['', `Sitemap: ${origin}/sitemap.xml`] : []), ''].join('\n');
}
