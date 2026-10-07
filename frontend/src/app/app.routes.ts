import { Injectable, inject } from '@angular/core';
import { ActivatedRouteSnapshot, ResolveFn, RouterStateSnapshot, Routes, TitleStrategy } from '@angular/router';
import { asNoradId } from './api/satellites.service';
import { HomePage } from './home/home';
import { coverTransition } from './motion/route-transition';
import { featuredSatellite } from './shared/featured';
import { cityDescription, cityFaq, cityName, cityTitle, faqJsonLd, issCity } from './pages/iss-city/iss-cities';
import { Seo, satelliteDescription, satelliteTitle } from './shared/seo';
import { PRICING_ENABLED } from './shared/site';

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

const cityOf = (route: ActivatedRouteSnapshot) => issCity(route.paramMap.get('city'));

const cityRouteTitle: ResolveFn<string> = (route) => {
  const city = cityOf(route);
  return city ? cityTitle(city) : $localize`:Page title:The ISS over your city`;
};

const cityRouteDescription: Description = (route) => {
  const city = cityOf(route);
  return city ? cityDescription(city) : $localize`:Meta description:The next passes of the ISS over fifty cities, computed as the page opens.`;
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
    data: { description: $localize`:Meta description:When the ISS or any satellite passes over you tonight: rise, peak and set times, visibility, sky chart and 3D globe, from fresh CelesTrak elements.` },
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
    path: 'iss',
    loadComponent: () => import('./pages/iss-cities/iss-cities').then((m) => m.IssCitiesPage),
    title: $localize`:Page title:The ISS over your city`,
    data: { description: $localize`:Meta description:When the International Space Station passes over Paris, London, New York, Montreal and forty-six other cities, computed live.` },
  },
  {
    path: 'iss/:city',
    loadComponent: () => import('./pages/iss-city/iss-city').then((m) => m.IssCityPage),
    title: cityRouteTitle,
    data: { description: cityRouteDescription },
  },
  {
    path: 'starlink',
    loadComponent: () => import('./pages/starlink/starlink').then((m) => m.StarlinkPage),
    title: $localize`:Page title:Starlink train tonight: when and where to see it`,
    data: { description: $localize`:Meta description:Saw a line of lights crossing the sky? The newest Starlink launches and when their train passes over you, from fresh CelesTrak elements.` },
  },
  {
    path: 'separations',
    loadComponent: () => import('./pages/separations/separations').then((m) => m.SeparationsPage),
    title: $localize`:Page title:What separated in orbit`,
    data: { description: $localize`:Meta description:Satellites releasing satellites, deployers emptying, bodies breaking apart: the latest separations in orbit, with the record behind each one and when to see the object pass over you.` },
  },
  {
    path: 'separations/:id',
    loadComponent: () => import('./pages/separation/separation').then((m) => m.SeparationPage),
    title: $localize`:Page title:Separation`,
    data: { description: $localize`:Meta description:One separation in orbit: what released what, when, both orbits, the record and when to see it pass over you.` },
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
  // Unpublished while nothing is for sale: /pricing falls through to the 404 (see PRICING_ENABLED).
  ...(PRICING_ENABLED ? [{
    path: 'pricing',
    loadComponent: () => import('./pages/pricing/pricing').then((m) => m.PricingPage),
    title: $localize`:Page title:Plans and quotas`,
    data: { description: $localize`:Meta description:Free preview, Hobby and Pro quotas of the satellite pass prediction API.` },
  }] : []),
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
      jsonLd: this.structuredData(route, snapshot.url, description),
    });
  }

  /** The application on the home page; the trail from it on the pages below Satellites. */
  private structuredData(route: ActivatedRouteSnapshot, url: string, description: string | undefined): object | undefined {
    const path = route.routeConfig?.path;
    if (path === '') return description ? this.seo.home(description) : undefined;
    const satellites = { name: $localize`:Breadcrumb:Satellites`, path: '/satellites' };
    if (path === 'starlink') return this.seo.breadcrumbs([satellites, { name: 'Starlink', path: url }]);
    if (path === 'iss/:city') {
      // The questions the page shows, as a FAQPage; with the trail when the origin is known.
      const city = cityOf(route);
      if (!city) return undefined;
      const faq = faqJsonLd(cityFaq(city));
      const trail = this.seo.breadcrumbs([
        { name: $localize`:Breadcrumb:The ISS over your city`, path: '/iss' }, { name: cityName(city), path: url }]);
      return trail ? { '@context': 'https://schema.org', '@graph': [strip(faq), strip(trail)] } : faq;
    }
    if (path === 'satellites/:noradId') {
      // Only a featured satellite has a name before the API answers; the others go without.
      const featured = featuredOf(route);
      return featured ? this.seo.breadcrumbs([satellites, { name: featured.name, path: url }]) : undefined;
    }
    return undefined;
  }
}

/** A schema.org node without its own @context, to sit in a @graph. */
function strip(node: object): object {
  const { ['@context']: _, ...rest } = node as Record<string, unknown>;
  return rest;
}
