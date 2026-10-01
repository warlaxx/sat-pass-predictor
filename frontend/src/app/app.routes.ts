import { Injectable, inject } from '@angular/core';
import { ActivatedRouteSnapshot, ResolveFn, RouterStateSnapshot, Routes, TitleStrategy } from '@angular/router';
import { asNoradId } from './api/satellites.service';
import { HomePage } from './home/home';
import { coverTransition } from './motion/route-transition';
import { featuredSatellite } from './shared/featured';
import { Seo, satelliteDescription, satelliteTitle } from './shared/seo';

/** A route's meta description: a sentence, or one built from the route's parameters. */
type Description = string | ((route: ActivatedRouteSnapshot) => string);

const featuredOf = (route: ActivatedRouteSnapshot) => featuredSatellite(asNoradId(route.paramMap.get('noradId') ?? ''));

const satelliteRouteTitle: ResolveFn<string> = (route) => {
  const featured = featuredOf(route);
  return featured ? satelliteTitle(featured.name) : $localize`:Page title:Satellite`;
};

const satelliteRouteDescription: Description = (route) => {
  const featured = featuredOf(route);
  return featured
    ? satelliteDescription(featured.name, featured.blurb)
    : $localize`:Meta description:Orbit, freshness of the elements and the next passes of one satellite.`;
};

/**
 * The predictor is eager - it is the page most visitors come for, and the one the tests
 * of the query drive. Every other page is its own chunk: a reader of the pricing table
 * should not download Three.js's loader, and a reader of the globe should not download
 * the legal notice.
 */
const pages: Routes = [
  {
    path: '',
    component: HomePage,
    title: $localize`:Page title:ISS and satellite passes over your location`,
    data: { description: $localize`:Meta description:When the ISS or any satellite passes over you tonight: rise, peak and set times, visibility, sky chart and 3D globe, computed with Orekit from fresh CelesTrak elements.` },
  },
  {
    path: 'satellites',
    loadComponent: () => import('./pages/satellites/satellites').then((m) => m.SatellitesPage),
    title: $localize`:Page title:Satellites to see in the night sky`,
    data: { description: $localize`:Meta description:The ISS, Tiangong, Hubble and other satellites worth following, and a search over the whole CelesTrak catalogue of active satellites.` },
  },
  {
    path: 'satellites/:noradId',
    loadComponent: () => import('./pages/satellite/satellite').then((m) => m.SatellitePage),
    title: satelliteRouteTitle,
    data: { description: satelliteRouteDescription },
  },
  {
    path: 'starlink',
    loadComponent: () => import('./pages/starlink/starlink').then((m) => m.StarlinkPage),
    title: $localize`:Page title:Starlink train tonight: when and where to see it`,
    data: { description: $localize`:Meta description:Saw a line of lights crossing the sky? The newest Starlink launches and when their train passes over you, from fresh CelesTrak elements.` },
  },
  {
    path: 'alerts',
    loadComponent: () => import('./pages/alerts/alerts').then((m) => m.AlertsPage),
    title: $localize`:Page title:Satellite pass reminders in your calendar`,
    data: { description: $localize`:Meta description:Calendar reminders before every potentially visible pass of the ISS or any satellite, as an .ics file for any calendar app.` },
  },
  {
    path: 'developers',
    loadComponent: () => import('./pages/developers/developers').then((m) => m.DevelopersPage),
    title: $localize`:Page title:Satellite pass prediction API`,
    data: { description: $localize`:Meta description:A REST API for satellite passes: SGP4 via Orekit, Doppler shift, batch predictions over many sites, RFC 9457 errors.` },
  },
  {
    path: 'pricing',
    loadComponent: () => import('./pages/pricing/pricing').then((m) => m.PricingPage),
    title: $localize`:Page title:Plans and quotas`,
    data: { description: $localize`:Meta description:Free preview, Hobby and Pro quotas of the satellite pass prediction API.` },
  },
  {
    path: 'methodology',
    loadComponent: () => import('./pages/methodology/methodology').then((m) => m.MethodologyPage),
    title: $localize`:Page title:How satellite passes are computed`,
    data: { description: $localize`:Meta description:How the passes are computed with SGP4 and Orekit, how they were validated against Skyfield, and what the numbers are worth.` },
  },
  {
    path: 'status',
    loadComponent: () => import('./pages/status/status').then((m) => m.StatusPage),
    title: $localize`:Page title:Service status`,
    data: { description: $localize`:Meta description:Live state of the prediction backend and of the satellite catalogue.` },
  },
  {
    path: 'legal',
    loadComponent: () => import('./pages/legal/legal').then((m) => m.LegalPage),
    title: $localize`:Page title:Legal notice, terms and privacy`,
    data: { description: $localize`:Meta description:Legal notice, terms of use and privacy policy of NextPass.` },
  },
  {
    path: '**',
    loadComponent: () => import('./pages/not-found/not-found').then((m) => m.NotFoundPage),
    title: $localize`:Page title:Page not found`,
    data: { noindex: true },
  },
];

/** Every page waits for the curtain before it swaps in (see `RouteTransition`). */
export const routes: Routes = pages.map(route => ({ ...route, canActivate: [coverTransition] }));

/**
 * "Page · NextPass" in the tab, and the head tags a search engine reads: the
 * route's description, canonical and language alternates, Open Graph (see `Seo`).
 *
 * The pages are prerendered (app.routes.server.ts), so these tags are in the HTML a
 * crawler downloads, not only in the DOM after the script has run.
 */
@Injectable({ providedIn: 'root' })
export class SiteTitleStrategy extends TitleStrategy {
  private readonly seo = inject(Seo);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    let route = snapshot.root;
    while (route.firstChild) route = route.firstChild;
    const describe = route.data['description'] as Description | undefined;
    const description = typeof describe === 'function' ? describe(route) : describe;
    this.seo.apply(snapshot.url, {
      title: this.buildTitle(snapshot),
      description,
      noindex: route.data['noindex'] === true,
      jsonLd: route.routeConfig?.path === '' && description ? this.seo.application(description) : undefined,
    });
  }
}
