/**
 * The separation events prerendered at build time: the newest ones, fetched from the API
 * once, before `ng build`, and written to a file the server bundle reads
 * (src/app/separations-snapshot.server.ts). Rendering then needs no network: a page is
 * either in the snapshot, with its title, heading and record in the HTML, or rendered in
 * the browser as before.
 *
 * The backend sleeps on Render's free plan, so the list is asked for a few times with a
 * long timeout, which wakes it. Whatever goes wrong, the build goes on: an unreachable API
 * means an empty snapshot and no event page prerendered, never a failed deployment.
 */

/** The API every deployment relays /api to (vercel.json). PRERENDER_API overrides it; `none` skips. */
export const DEFAULT_API = 'https://sat-pass-predictor-api.onrender.com';

/** As many events as the /separations page lists. */
export const SNAPSHOT_LIMIT = 50;

/** PRERENDER_API, else the production API; undefined when the snapshot is switched off. */
export function snapshotApi(env) {
  const explicit = (env.PRERENDER_API ?? '').trim();
  if (explicit.toLowerCase() === 'none') return undefined;
  return (explicit || DEFAULT_API).replace(/\/+$/, '');
}

/**
 * The newest events, each as `GET /api/separations/{id}` answers it, keyed by the id the
 * list gives - the one /separations links to. An event whose detail cannot be fetched is
 * left out; a list that cannot be fetched gives an empty snapshot.
 */
export async function fetchSnapshot(api, {
  fetch = globalThis.fetch,
  limit = SNAPSHOT_LIMIT,
  listAttempts = 3,
  listTimeoutMs = 60_000,
  eventTimeoutMs = 20_000,
  concurrency = 4,
  log = console.log,
} = {}) {
  const getJson = async (path, timeoutMs) => {
    const response = await fetch(`${api}${path}`, {
      headers: { accept: 'application/json' },
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  };

  let ids;
  for (let attempt = 1; attempt <= listAttempts && !ids; attempt++) {
    try {
      const list = await getJson(`/api/separations?limit=${limit}`, listTimeoutMs);
      ids = list.events.map((event) => event.id);
    } catch (error) {
      log(`Separations, attempt ${attempt}/${listAttempts}: ${error.message}`);
    }
  }
  if (!ids) return {};

  const events = {};
  const queue = [...ids];
  const worker = async () => {
    for (let id = queue.shift(); id !== undefined; id = queue.shift()) {
      try {
        events[id] = await getJson(`/api/separations/${encodeURIComponent(id)}`, eventTimeoutMs);
      } catch (error) {
        log(`Separation ${id} left out: ${error.message}`);
      }
    }
  };
  await Promise.all(Array.from({ length: concurrency }, worker));
  // In the list's order, newest first, whatever order the requests finished in.
  return Object.fromEntries(ids.filter((id) => id in events).map((id) => [id, events[id]]));
}
