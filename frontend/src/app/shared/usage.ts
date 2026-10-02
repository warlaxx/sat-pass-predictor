/**
 * The actions counted on the separation pages, for the phase 3.2 decision (ABD-8).
 * The same closed list as the backend's `UsageEvent`: any other name is ignored there.
 */
export type UsageEvent =
  | 'list-open-event'      // from the list to one event
  | 'list-show-table'      // the month chart opened as a table: analyst side
  | 'event-use-position'   // passes from the reader's own position: observer side
  | 'event-open-pass'      // a pass opened in the predictor: observer side
  | 'event-open-fragment'; // a fragment's own page: analyst side

/**
 * Counts one action: a daily counter goes up by one on the backend, and nothing about
 * the reader is sent or kept - no cookie, no identifier, not even a body. A beacon,
 * because it survives the navigation the click often starts and nobody waits for it;
 * a browser without one, or the prerenderer, simply counts nothing.
 */
export function countUsage(event: UsageEvent): void {
  if (typeof navigator === 'undefined' || typeof navigator.sendBeacon !== 'function') return;
  try {
    navigator.sendBeacon(`/api/usage/${event}`);
  } catch {
    // A lost count is not worth an error on the page.
  }
}
