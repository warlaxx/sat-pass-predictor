/**
 * The addresses and facts more than one page quotes.
 *
 * A quota written on the pricing page and again in the developer guide is a quota that
 * will be wrong on one of them the day it moves. Each figure here mirrors a source of
 * truth elsewhere - named beside it - and is repeated only because a static page cannot
 * ask the backend.
 */

/** The public backend origin; customers call `/v1` on it directly (docs/api-access.md). */
export const API_ORIGIN = 'https://sat-pass-predictor-api.onrender.com';
export const ACCOUNT_URL = `${API_ORIGIN}/account/`;
export const SWAGGER_URL = `${API_ORIGIN}/docs`;
export const REPOSITORY_URL = 'https://github.com/warlaxx/sat-pass-predictor';

/** The plans of docs/billing.md, as enforced by the backend's quota admission. */
export interface Plan {
  readonly id: 'free' | 'hobby' | 'pro';
  readonly name: string;
  readonly requests: number;
  readonly period: 'UTC day' | 'UTC calendar month';
  readonly perMinute: number;
  readonly keys: number;
  readonly summary: string;
}

export const PLANS: readonly Plan[] = [
  {
    id: 'free', name: $localize`Free preview`, requests: 100, period: 'UTC day', perMinute: 10, keys: 1,
    summary: $localize`Try the API and prototype an integration.`,
  },
  {
    id: 'hobby', name: 'Hobby', requests: 25_000, period: 'UTC calendar month', perMinute: 30, keys: 1,
    summary: $localize`A ground station, a club tracker, a personal dashboard.`,
  },
  {
    id: 'pro', name: 'Pro', requests: 250_000, period: 'UTC calendar month', perMinute: 120, keys: 1,
    summary: $localize`A product that serves predictions to its own users.`,
  },
];

/**
 * Whether the plans page is published: its route, its header and footer links.
 *
 * Off while nothing is for sale (backend `BILLING_ENABLED` is off too, docs/billing.md):
 * `/pricing` then answers as an unknown page, leaves the sitemap, and the links that
 * cite plans point to the developer guide's limits instead. The page itself is kept;
 * turning this on, with billing, publishes it again.
 */
export const PRICING_ENABLED = false;

/** Where a sentence about plans and quotas links to. */
export const PLANS_LINK: { readonly path: string; readonly fragment?: string } =
  PRICING_ENABLED ? { path: '/pricing' } : { path: '/developers', fragment: 'limits' };

/** The anonymous budget this web page shares with every visitor (docs/api-access.md). */
export const DEMO_LIMITS = { daily: 200, perMinute: 20 } as const;

/** Batch bounds of `BatchQuery` (docs/api-access.md#batch-requests). */
export const BATCH_LIMITS = { satellites: 10, sites: 10, predictions: 25 } as const;

/**
 * The operator of the site, as French law requires it to be published (LCEN art. 6).
 *
 * The publisher is a private individual, so there is no legal form, SIREN or VAT number
 * to publish; add them here, and to the legal page, the day a business is registered to
 * sell the paid plans. Deliberately empty: these are facts about a person, not something
 * code may invent. The legal page renders a visible "to be completed" marker for every blank
 * field, so a deployment cannot look finished while it is not.
 */
export const OPERATOR = {
  name: '',
  address: '',
  email: 'contact@nextpass.space',
  publicationDirector: '',
} as const;
