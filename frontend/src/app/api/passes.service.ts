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

  /**
   * Asking again with the query already on screen computes again: the window starts now,
   * so the same form an hour later is a different answer. The signal would see no change
   * in the same object and leave the old result in place.
   */
  search(query: PassQuery): void {
    if (this.query() === query) this.resource.reload();
    else this.query.set(query);
  }

  reload(): void {
    this.resource.reload();
  }

  private readonly featuredWanted = signal(false);

  /**
   * The next passes of the ISS over Lyon, for the home page's countdown (ABD-31).
   *
   * A request of its own rather than a search: `/api/featured-pass` takes no parameter
   * and is not metered, so a visitor who asked for nothing spends none of the quota the
   * whole site shares, and does not count as a search. It stays idle until asked for -
   * the prerender must not bake a pass into the HTML that would be stale on arrival.
   */
  readonly featured = httpResource<PassesResponse>(() => this.featuredWanted() ? '/api/featured-pass' : undefined);

  /** Asks for the featured passes once; later calls keep the answer already on screen. */
  loadFeatured(): void {
    this.featuredWanted.set(true);
  }
}
