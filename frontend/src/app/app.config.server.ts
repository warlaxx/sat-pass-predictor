import { ApplicationConfig, mergeApplicationConfig } from '@angular/core';
import { HttpBackend } from '@angular/common/http';
import { provideServerRendering, withRoutes } from '@angular/ssr';
import { appConfig } from './app.config';
import { serverRoutes } from './app.routes.server';
import { SeparationSnapshotBackend } from './separations-snapshot.server';

const serverConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(withRoutes(serverRoutes)),
    // Prerendered separation pages read their event from the build's snapshot.
    { provide: HttpBackend, useClass: SeparationSnapshotBackend },
  ],
};

export const config = mergeApplicationConfig(appConfig, serverConfig);
