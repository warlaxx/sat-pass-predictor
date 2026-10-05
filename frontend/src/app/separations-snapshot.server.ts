import { Injectable } from '@angular/core';
import { FetchBackend, HttpEvent, HttpRequest, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { SeparationEvent, SeparationSummary } from './api/separations.model';

/**
 * The separation events prerendered by this build, server side only.
 *
 * `npm run build` fetches them before `ng build` (scripts/separations-snapshot.mjs) and
 * names the file in NG_SEPARATIONS_SNAPSHOT. Without it - a plain `ng build`, an
 * unreachable API - the snapshot is empty and every event page is left to the browser.
 */

// The app is typed without Node; these are the only two Node APIs the server bundle uses.
declare const process: {
  readonly env: Readonly<Record<string, string | undefined>>;
  getBuiltinModule(id: 'node:fs'): { readFileSync(path: string, encoding: 'utf8'): string };
};

let events: Readonly<Record<string, SeparationEvent>> | undefined;

/** The events, by the id /separations links to; read once per process. */
export function separationSnapshot(): Readonly<Record<string, SeparationEvent>> {
  if (events === undefined) {
    const file = process.env['NG_SEPARATIONS_SNAPSHOT'];
    try {
      events = file ? JSON.parse(process.getBuiltinModule('node:fs').readFileSync(file, 'utf8')) : {};
    } catch {
      events = {};
    }
  }
  return events!;
}

/**
 * The newest events of the snapshot as `GET /api/separations` lists them, for the home
 * page. The snapshot is in the list's order, newest first. The first child is the one
 * that names the event, as in the backend's list.
 */
export function latestSeparations(limit: number): SeparationSummary[] {
  return Object.values(separationSnapshot()).slice(0, limit).map((event) => {
    const first = event.children.find((child) => child.id === event.id) ?? event.children[0];
    return {
      id: event.id,
      kind: event.kind,
      date: event.date,
      parentName: event.parent?.name ?? null,
      parentOwner: event.parent?.owner ?? null,
      parentState: event.parent?.state ?? null,
      firstChildName: first?.name ?? null,
      firstChildNoradId: first?.noradId ?? null,
      children: event.childCount,
      orbit: first?.orbit ?? { perigeeKm: null, apogeeKm: null, inclinationDeg: null, orbitClass: null },
      inOrbit: first?.inOrbit ?? false,
    };
  });
}

const EVENT_URL = /^\/api\/separations\/([^/]+)$/;

/**
 * Answers `GET /api/separations/{id}` from the snapshot, and lets every other request
 * through. It replaces the backend rather than adding an interceptor so that the
 * hydration transfer cache, which sits after the interceptors, still records the
 * answer: the browser then adopts the prerendered page without asking the API again.
 * A FetchBackend still, or Angular warns that server rendering should use fetch.
 */
@Injectable()
export class SeparationSnapshotBackend extends FetchBackend {
  override handle(request: HttpRequest<unknown>): Observable<HttpEvent<unknown>> {
    // On the server, relative URLs arrive resolved against a placeholder origin.
    const id = request.method === 'GET' ? EVENT_URL.exec(new URL(request.url, 'http://localhost').pathname)?.[1] : undefined;
    const event = id === undefined ? undefined : separationSnapshot()[decodeURIComponent(id)];
    return event ? of(new HttpResponse({ status: 200, url: request.url, body: event })) : super.handle(request);
  }
}
