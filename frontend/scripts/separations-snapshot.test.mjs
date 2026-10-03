import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_API, fetchSnapshot, snapshotApi } from './separations-snapshot.mjs';

const json = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });

/** A fake API: the list, then one answer per event; `fail` names the paths that throw. */
function api(ids, { fail = [], listFailures = 0 } = {}) {
  const calls = [];
  const fetch = async (url) => {
    const path = new URL(url).pathname + new URL(url).search;
    calls.push(path);
    if (path.startsWith('/api/separations?')) {
      if (listFailures-- > 0) throw new Error('The operation was aborted due to timeout');
      return json({ events: ids.map((id) => ({ id })) });
    }
    if (fail.some((failing) => path.endsWith(failing))) return json({ title: 'Not found' }, 404);
    const id = path.split('/').pop();
    return json({ id, kind: 'RELEASE' });
  };
  return { fetch, calls };
}

const quiet = { log: () => {} };

test('the API is the production one, PRERENDER_API, or none at all', () => {
  assert.equal(snapshotApi({}), DEFAULT_API);
  assert.equal(snapshotApi({ PRERENDER_API: 'http://localhost:8080/' }), 'http://localhost:8080');
  assert.equal(snapshotApi({ PRERENDER_API: 'none' }), undefined);
});

test('the snapshot holds each listed event, newest first, keyed by its id', async () => {
  const { fetch, calls } = api(['S100685', 'S100643', 'S69731']);
  const snapshot = await fetchSnapshot('https://api.example', { fetch, ...quiet });
  assert.deepEqual(Object.keys(snapshot), ['S100685', 'S100643', 'S69731']);
  assert.deepEqual(snapshot.S100685, { id: 'S100685', kind: 'RELEASE' });
  assert.equal(calls[0], '/api/separations?limit=50');
});

test('a sleeping backend is asked again until it answers', async () => {
  const { fetch } = api(['S100685'], { listFailures: 2 });
  const snapshot = await fetchSnapshot('https://api.example', { fetch, listAttempts: 3, ...quiet });
  assert.deepEqual(Object.keys(snapshot), ['S100685']);
});

test('an unreachable API gives an empty snapshot, not an error', async () => {
  const fetch = async () => { throw new TypeError('fetch failed'); };
  assert.deepEqual(await fetchSnapshot('https://api.example', { fetch, ...quiet }), {});
});

test('an event whose detail fails is left out, the others kept', async () => {
  const { fetch } = api(['S1', 'S2', 'S3'], { fail: ['/S2'] });
  const snapshot = await fetchSnapshot('https://api.example', { fetch, ...quiet });
  assert.deepEqual(Object.keys(snapshot), ['S1', 'S3']);
});
