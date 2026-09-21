import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../frontend/package.json', import.meta.url));
const { JSDOM } = require('jsdom');
const root = new URL('../backend/src/main/resources/static/account/', import.meta.url);
const html = readFileSync(new URL('index.html', root), 'utf8');
const script = readFileSync(new URL('dashboard.js', root), 'utf8');
const dashboard = { keyId: 'key-id', plan: 'standard', active: true, usedToday: 9, dailyLimit: 100, minuteLimit: 10, usageDay: '2026-09-20' };
async function settle() { for (let i = 0; i < 8; i++) await new Promise(resolve => setImmediate(resolve)); }
function setup(handler) {
  const dom = new JSDOM(html, { url: 'https://api.example/account/', runScripts: 'outside-only' });
  const requests = [];
  dom.window.fetch = async (path, options) => {
    requests.push({ path, ...options });
    const [status, body] = handler(path, options, requests);
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  };
  dom.window.eval(script);
  return { dom, el: id => dom.window.document.getElementById(id), requests };
}

test('anonymous user sees sign in and no customer data', async () => {
  const { dom, el } = setup(() => [401, {}]);
  await settle();
  assert.equal(el('signin').hidden, false);
  assert.equal(el('dashboard').hidden, true);
  assert.equal(el('secret').value, '');
  dom.window.close();
});

test('create and revoke use CSRF; raw key stays out of the example and is cleared on revoke', async () => {
  const secret = 'spp_example-secret';
  let revoked = false;
  const { dom, el, requests } = setup((path, options) => {
    if (path.endsWith('/csrf')) return [200, { headerName: 'X-CSRF-TOKEN', token: 'csrf-value' }];
    if (options.method === 'POST') return [200, { id: 'key-id', secret }];
    if (options.method === 'DELETE') { revoked = true; return [204]; }
    return [200, { ...dashboard, active: !revoked }];
  });
  await settle();
  assert.match(el('usage').textContent, /9 \/ 100/);
  el('regenerate').click(); await settle();
  assert.equal(el('secret').value, secret);
  assert.equal(el('secret-box').hidden, false);
  assert.ok(!el('example').textContent.includes(secret));
  const post = requests.find(r => r.method === 'POST');
  assert.equal(post.headers['X-CSRF-TOKEN'], 'csrf-value');
  assert.equal(post.cache, 'no-store');
  el('confirm-revoke').click(); await settle();
  assert.equal(el('secret').value, '');
  assert.equal(el('revoke').disabled, true);
  assert.equal(requests.find(r => r.method === 'DELETE').headers['X-CSRF-TOKEN'], 'csrf-value');
  dom.window.close();
});

test('expiry during a mutation clears secret and account information', async () => {
  let expired = false;
  const { dom, el } = setup(path => expired ? [401, {}] : [200, dashboard]);
  await settle();
  el('secret').value = 'old-secret'; el('secret-box').hidden = false;
  expired = true;
  el('regenerate').click(); await settle();
  assert.equal(el('secret').value, '');
  assert.equal(el('dashboard').hidden, true);
  assert.equal(el('signin').hidden, false);
  dom.window.close();
});

test('lost refresh does not discard the only copy of a newly issued key', async () => {
  let created = false;
  const { dom, el } = setup((path, options) => {
    if (path.endsWith('/csrf')) return [200, { headerName: 'X-CSRF-TOKEN', token: 'csrf-value' }];
    if (options.method === 'POST') { created = true; return [200, { secret: 'new-secret' }]; }
    return created ? [503, { detail: 'Please retry later.' }] : [200, dashboard];
  });
  await settle(); el('regenerate').click(); await settle();
  assert.equal(el('secret').value, 'new-secret');
  assert.equal(el('secret-box').hidden, false);
  assert.equal(el('message').textContent, 'Please retry later.');
  dom.window.dispatchEvent(new dom.window.Event('pagehide'));
  assert.equal(el('secret').value, '');
  dom.window.close();
});

test('billing controls are opt-in and monthly usage is displayed', async () => {
  const { dom, el } = setup(path => path.endsWith('/billing') ? [200, { enabled: true }]
    : [200, { ...dashboard, plan: 'hobby', usedThisMonth: 1200, monthlyLimit: 25000 }]);
  await settle();
  assert.equal(el('billing').hidden, false);
  assert.equal(el('usage').textContent, '1200 / 25000 calls');
  assert.equal(el('quota').max, 25000);
  assert.match(el('reset').textContent, /first day of the month/);
  dom.window.close();
});

test('checkout is CSRF-protected and a billing failure leaves the account usable', async () => {
  const { dom, el, requests } = setup(path => {
    if (path.endsWith('/csrf')) return [200, { headerName: 'X-CSRF-TOKEN', token: 'csrf-value' }];
    if (path.includes('/checkout')) return [503, { detail: 'Billing unavailable. Please retry.' }];
    if (path.endsWith('/billing')) return [200, { enabled: true }];
    return [200, dashboard];
  });
  await settle(); el('hobby').click(); await settle();
  const checkout = requests.find(r => r.path.includes('/checkout'));
  assert.equal(checkout.path, '/account/api/billing/checkout?plan=hobby');
  assert.equal(checkout.method, 'POST');
  assert.equal(checkout.headers['X-CSRF-TOKEN'], 'csrf-value');
  assert.equal(el('hobby').disabled, false);
  assert.equal(el('dashboard').hidden, false);
  assert.equal(el('message').textContent, 'Billing unavailable. Please retry.');
  dom.window.close();
});
