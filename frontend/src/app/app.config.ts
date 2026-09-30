import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TitleStrategy, provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { SiteTitleStrategy, routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // No zone.js. Every component is OnPush and every piece of state is a signal, so
    // there is nothing left for zone patching to discover.
    provideZonelessChangeDetection(),
    // A new page opens at its top, and a #fragment scrolls to its section - including
    // the result sections of the predictor, whose links keep the query string.
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' }),
    ),
    { provide: TitleStrategy, useClass: SiteTitleStrategy },
    // Calls go to /api and are relayed to http://localhost:8080 by proxy.conf.json in
    // development: no CORS to configure on the Spring side.
    provideHttpClient(withFetch()),
  ],
};
