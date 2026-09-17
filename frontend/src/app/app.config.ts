import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // No zone.js. Every component is OnPush and every piece of state is a signal, so
    // there is nothing left for zone patching to discover.
    provideZonelessChangeDetection(),
    provideRouter(routes),
    // Calls go to /api and are relayed to http://localhost:8080 by proxy.conf.json in
    // development: no CORS to configure on the Spring side.
    provideHttpClient(withFetch()),
  ],
};
