import { Injectable, computed, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { PassesResponse } from './passes.model';
import { PassQuery, toParams } from './passes.query';

/**
 * The only thing that talks to the backend.
 *
 * The request is a function of a signal, so a new query re-fetches on its own; nothing
 * here subscribes, cancels or tracks a loading flag by hand. Before the first search the
 * factory returns `undefined`, which leaves the resource idle rather than firing a
 * request nobody asked for.
 *
 * The three states are exposed as they are, not folded into a boolean. A page that draws
 * "loading", "error" and "no pass in this window" as the same spinner is a page that
 * lies twice out of three.
 */
@Injectable({ providedIn: 'root' })
export class PassesApi {
  private readonly query = signal<PassQuery | undefined>(undefined);

  readonly resource = httpResource<PassesResponse>(() => {
    const query = this.query();
    return query ? { url: '/api/passes', params: toParams(query) } : undefined;
  });

  /** The query that produced what is currently on screen, or undefined before the first. */
  readonly lastQuery = computed(() => this.query());

  search(query: PassQuery): void {
    this.query.set(query);
  }

  reload(): void {
    this.resource.reload();
  }
}
