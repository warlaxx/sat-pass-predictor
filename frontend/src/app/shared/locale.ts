import { LOCALE_ID, inject } from '@angular/core';

/**
 * The two languages of the site, and where each one lives.
 *
 * Each language is its own build (`ng build` with `localize`, see angular.json): English
 * at the root, where every address published before the translation still works, and
 * French under /fr/. Moving between them is a full page load into the other build, never
 * a router navigation - the router of one build knows nothing of the other's base href.
 */
export type SiteLanguage = 'en' | 'fr';

export interface LanguageInfo {
  readonly code: SiteLanguage;
  /** The path prefix of the build, without a trailing slash: '' or '/fr'. */
  readonly prefix: string;
  /** The language's own name for itself, as a switch shows it. */
  readonly label: string;
  readonly short: string;
  /** Open Graph's `og:locale`. */
  readonly ogLocale: string;
}

export const LANGUAGES: Readonly<Record<SiteLanguage, LanguageInfo>> = {
  en: { code: 'en', prefix: '', label: 'English', short: 'EN', ogLocale: 'en_GB' },
  fr: { code: 'fr', prefix: '/fr', label: 'Français', short: 'FR', ogLocale: 'fr_FR' },
};

export function languageOf(localeId: string): SiteLanguage {
  return localeId.toLowerCase().startsWith('fr') ? 'fr' : 'en';
}

/** The language of the running build. */
export function currentLanguage(): SiteLanguage {
  return languageOf(inject(LOCALE_ID));
}

/**
 * The address of a router path in a language: '/satellites' becomes '/fr/satellites'.
 *
 * The query string and fragment are dropped: the address is a page, as a link between
 * languages or a canonical URL names it, not a particular search on it.
 */
export function pathIn(language: SiteLanguage, routerUrl: string): string {
  const path = routerUrl.split(/[?#]/)[0] || '/';
  const prefix = LANGUAGES[language].prefix;
  if (path === '/') return prefix ? `${prefix}/` : '/';
  return prefix + path;
}

/** The same page in a language, keeping the search and fragment: what a language switch opens. */
export function addressIn(language: SiteLanguage, routerUrl: string): string {
  const rest = routerUrl.slice(routerUrl.split(/[?#]/)[0].length);
  return pathIn(language, routerUrl) + rest;
}
