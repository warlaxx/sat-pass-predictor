import { PrerenderFallback, RenderMode, ServerRoute } from '@angular/ssr';
import { FEATURED } from './shared/featured';
import { separationSnapshot } from './separations-snapshot.server';

/**
 * Which pages are written to HTML at build time.
 *
 * The site is served as static files by Vercel, so there is no server to render on
 * request: a page is either prerendered here or left to the browser. Prerendered pages
 * carry their title, description and text in the HTML a crawler downloads; the others
 * start as an empty shell until the script runs.
 *
 * Passes are never prerendered - they would be stale by the time anyone reads them. A
 * featured satellite's page carries its name and its card's text; its orbit and passes
 * come from the API, in the browser. The newest separation events, fetched once by
 * `npm run build`, carry their heading and record; their passes, too, come later.
 */
export const serverRoutes: ServerRoute[] = [
  {
    path: 'satellites/:noradId',
    renderMode: RenderMode.Prerender,
    getPrerenderParams: async () =>
      FEATURED.flatMap(group => group.satellites).map(satellite => ({ noradId: String(satellite.noradId) })),
    // Any other catalogue number is a valid page, rendered in the browser.
    fallback: PrerenderFallback.Client,
  },
  {
    path: 'separations/:id',
    renderMode: RenderMode.Prerender,
    getPrerenderParams: async () => Object.keys(separationSnapshot()).map(id => ({ id })),
    // Older events, and those imported since the build, are rendered in the browser.
    fallback: PrerenderFallback.Client,
  },
  { path: '**', renderMode: RenderMode.Prerender },
];
