import { ApplicationConfig, mergeApplicationConfig } from '@angular/core';
import { HttpBackend } from '@angular/common/http';
import { provideServerRendering, withRoutes } from '@angular/ssr';
import { appConfig } from './app.config';
import { serverRoutes } from './app.routes.server';
import { SeparationSnapshotBackend, latestSeparations } from './separations-snapshot.server';
import { HOME_SEPARATIONS, PRERENDERED_SEPARATIONS } from './home/latest-separations';

const serverConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(withRoutes(serverRoutes)),
    // Prerendered separation pages read their event from the build's snapshot.
    { provide: HttpBackend, useClass: SeparationSnapshotBackend },
    // And so does the home page's list of the newest ones.
    { provide: PRERENDERED_SEPARATIONS, useFactory: () => latestSeparations(HOME_SEPARATIONS) },
  ],
};

export const config = mergeApplicationConfig(appConfig, serverConfig);
