import { Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { RouterStateSnapshot, Routes, TitleStrategy } from '@angular/router';
import { HomePage } from './home/home';

const SITE = 'Sat Pass Predictor';

/**
 * The predictor is eager - it is the page most visitors come for, and the one the tests
 * of the query drive. Every other page is its own chunk: a reader of the pricing table
 * should not download Three.js's loader, and a reader of the globe should not download
 * the legal notice.
 */
export const routes: Routes = [
  {
    path: '',
    component: HomePage,
    title: 'Satellite pass predictions',
    data: { description: 'When a satellite passes over you: rise, peak and set times, sky chart and globe, computed with Orekit from fresh CelesTrak elements.' },
  },
  {
    path: 'satellites',
    loadComponent: () => import('./pages/satellites/satellites').then((m) => m.SatellitesPage),
    title: 'Satellites',
    data: { description: 'Popular satellites to follow, and a search over the whole CelesTrak catalogue.' },
  },
  {
    path: 'satellites/:noradId',
    loadComponent: () => import('./pages/satellite/satellite').then((m) => m.SatellitePage),
    title: 'Satellite',
    data: { description: 'Orbit, freshness of the elements and the next passes of one satellite.' },
  },
  {
    path: 'alerts',
    loadComponent: () => import('./pages/alerts/alerts').then((m) => m.AlertsPage),
    title: 'Pass reminders',
    data: { description: 'Calendar reminders before every potentially visible pass of a satellite.' },
  },
  {
    path: 'developers',
    loadComponent: () => import('./pages/developers/developers').then((m) => m.DevelopersPage),
    title: 'Satellite pass prediction API',
    data: { description: 'A REST API for satellite passes: SGP4 via Orekit, Doppler shift, batch predictions over many sites, RFC 9457 errors.' },
  },
  {
    path: 'pricing',
    loadComponent: () => import('./pages/pricing/pricing').then((m) => m.PricingPage),
    title: 'Plans and quotas',
    data: { description: 'Free preview, Hobby and Pro quotas of the satellite pass prediction API.' },
  },
  {
    path: 'methodology',
    loadComponent: () => import('./pages/methodology/methodology').then((m) => m.MethodologyPage),
    title: 'Methodology',
    data: { description: 'How the passes are computed, how they were validated against Skyfield, and what the numbers are worth.' },
  },
  {
    path: 'status',
    loadComponent: () => import('./pages/status/status').then((m) => m.StatusPage),
    title: 'Service status',
    data: { description: 'Live state of the prediction backend and of the satellite catalogue.' },
  },
  {
    path: 'legal',
    loadComponent: () => import('./pages/legal/legal').then((m) => m.LegalPage),
    title: 'Legal notice, terms and privacy',
    data: { description: 'Legal notice, terms of use and privacy policy of Sat Pass Predictor.' },
  },
  {
    path: '**',
    loadComponent: () => import('./pages/not-found/not-found').then((m) => m.NotFoundPage),
    title: 'Page not found',
  },
];

/**
 * "Page · Sat Pass Predictor" in the tab, and the route's sentence in the description.
 *
 * The site is rendered in the browser, so this does not make it indexable by itself; it
 * makes every tab, bookmark and history entry say which page it is, and gives a crawler
 * that does run scripts something better than one description for eight pages.
 */
@Injectable({ providedIn: 'root' })
export class SiteTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const page = this.buildTitle(snapshot);
    this.title.setTitle(page ? `${page} · ${SITE}` : SITE);
    let route = snapshot.root;
    while (route.firstChild) route = route.firstChild;
    const description = route.data['description'] as string | undefined;
    if (description) this.meta.updateTag({ name: 'description', content: description });
  }
}
