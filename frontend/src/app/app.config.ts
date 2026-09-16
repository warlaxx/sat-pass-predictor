import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // Les appels partent sur /api et sont relayes vers http://localhost:8080
    // par proxy.conf.json en dev : pas de CORS a configurer cote Spring.
    provideHttpClient(withFetch()),
  ],
};
