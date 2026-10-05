import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checks, confirm, decide, issueBody, probe } from './uptime.mjs';

const [website, backend, health, separations] = checks('https://site.example', 'https://api.example');
const answer = (status, body) => async () => ({ ok: status >= 200 && status < 300, status, text: async () => body });
const noSleep = async () => {};
const up = (id) => ({ id, up: true });
const down = (id) => ({ id, up: false });
const openIssue = (ids) => ({ number: 7, body: `<!-- uptime-checks: ${ids.join(',')} -->\nA check failed.` });

test('fast checks leave the database alone', () => {
  assert.deepEqual([website, backend].map((c) => c.deep), [false, false]);
  assert.deepEqual([health, separations].map((c) => c.deep), [true, true]);
  assert.equal(backend.url, 'https://api.example/actuator/health/liveness');
  assert.equal(separations.url, 'https://site.example/api/separations');
});

test('a probe is up only on a 2xx with the expected body', async () => {
  assert.equal((await probe(health, { fetchImpl: answer(200, '{"status":"UP"}') })).up, true);
  assert.equal((await probe(health, { fetchImpl: answer(200, '{"status":"DOWN"}') })).up, false);
  assert.equal((await probe(separations, { fetchImpl: answer(200, '<html>an error page</html>') })).detail, 'HTTP 200, unexpected body');
  assert.equal((await probe(separations, { fetchImpl: answer(502, '') })).detail, 'HTTP 502');
  assert.equal((await probe(website, { fetchImpl: answer(200, '<app-root></app-root>') })).up, true);
});

test('a timeout is reported as such', async () => {
  // AbortSignal.timeout's timer does not hold the event loop open; a real socket would.
  const hang = (_url, { signal }) => new Promise((_, reject) => {
    const socket = setTimeout(() => {}, 1_000);
    signal.addEventListener('abort', () => { clearTimeout(socket); reject(signal.reason); });
  });
  const result = await probe(backend, { fetchImpl: hang, timeoutMs: 10 });
  assert.equal(result.up, false);
  assert.equal(result.detail, 'no answer within 0.01 s');
});

test('one failure followed by a success is not an outage', async () => {
  let calls = 0;
  const flaky = async () => (++calls === 1 ? answer(502, '')() : answer(200, '{"status":"UP"}')());
  const result = await confirm(health, { fetchImpl: flaky, sleep: noSleep });
  assert.equal(result.up, true);
  assert.equal(result.detail, 'HTTP 200 (first attempt: HTTP 502)');
});

test('two consecutive failures are', async () => {
  const result = await confirm(health, { fetchImpl: answer(503, ''), sleep: noSleep });
  assert.equal(result.up, false);
  assert.equal(result.detail, 'HTTP 503, then HTTP 503');
});

test('an outage opens an issue, a quiet run does nothing', () => {
  assert.deepEqual(decide([up('website'), down('backend')], undefined), { action: 'open', ids: ['backend'] });
  assert.deepEqual(decide([up('website'), up('backend')], undefined), { action: 'none' });
});

test('an outage already open is not reported again every five minutes', () => {
  assert.deepEqual(decide([down('backend')], openIssue(['backend'])), { action: 'none' });
});

test('a check that joins the outage is added to it', () => {
  assert.deepEqual(
    decide([down('backend'), down('website')], openIssue(['backend'])),
    { action: 'comment', ids: ['backend', 'website'], added: ['website'] },
  );
});

test('the issue closes once every check it names is up again', () => {
  assert.deepEqual(decide([up('website'), up('backend')], openIssue(['backend'])), { action: 'close' });
});

test('a fast run cannot close a database outage it did not look at', () => {
  assert.deepEqual(decide([up('website'), up('backend')], openIssue(['separations'])), { action: 'none' });
  assert.deepEqual(decide([up('website'), up('backend'), up('health'), up('separations')], openIssue(['separations'])), { action: 'close' });
});

test('the issue body carries the checks it names and mentions the owner', () => {
  const results = [{ ...separations, up: false, millis: 61000, detail: 'HTTP 502, then HTTP 502' }];
  const body = issueBody(['separations'], results, { runUrl: 'https://github.com/run/1', owner: 'warlaxx' });
  assert.match(body, /^<!-- uptime-checks: separations -->/);
  assert.match(body, /@warlaxx /);
  assert.match(body, /\| \*\*down\*\* \| 61000 ms \| HTTP 502, then HTTP 502 \|/);
  assert.deepEqual(decide([up('separations')], { body }), { action: 'close' });
});
