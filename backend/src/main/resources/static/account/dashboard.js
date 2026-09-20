'use strict';
const el = id => document.getElementById(id);
let csrf;
let busy = false;
let current;
function clearSecret() { el('secret').value = ''; el('secret-box').hidden = true; }
function signedOut() {
  clearSecret(); csrf = undefined; current = undefined;
  el('dashboard').hidden = true; el('signin').hidden = false;
}
async function request(path, method = 'GET') {
  const headers = {};
  if (method !== 'GET') {
    csrf = await request('/account/api/csrf');
    headers[csrf.headerName] = csrf.token;
  }
  const response = await fetch(path, { method, headers, credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 401) { signedOut(); throw new Error('Sign in with GitHub to access your account.'); }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || 'Account service unavailable. Please try again.');
  }
  return response.status === 204 ? undefined : response.json();
}
async function refresh() {
  current = await request('/account/api/me');
  el('signin').hidden = true; el('dashboard').hidden = false;
  el('plan').textContent = current.plan === 'standard' ? 'Free preview' : current.plan;
  el('usage').textContent = `${current.usedToday} / ${current.dailyLimit} calls`;
  el('quota').max = current.dailyLimit; el('quota').value = current.usedToday;
  el('reset').textContent = `Usage for ${current.usageDay}. Resets at 00:00 UTC.`;
  el('rate').textContent = `${current.minuteLimit} calls / minute`;
  el('key-state').textContent = !current.keyId ? 'No key created yet.' : current.active ? `Active · ${current.keyId}` : 'Key revoked.';
  el('regenerate').textContent = current.keyId ? 'Regenerate API key' : 'Create API key';
  el('revoke').disabled = !current.active;
  el('confirm').hidden = !current.active; el('confirm').open = false;
}
async function action(work) {
  if (busy) return;
  busy = true;
  document.querySelectorAll('button').forEach(button => button.disabled = true);
  el('message').textContent = '';
  try { await work(); } catch (error) { el('message').textContent = error.message || 'Unable to reach the server. Please retry.'; }
  finally {
    busy = false;
    document.querySelectorAll('button').forEach(button => button.disabled = false);
    el('revoke').disabled = !current?.active;
  }
}
el('regenerate').addEventListener('click', () => action(async () => {
  clearSecret();
  const key = await request('/account/api/key', 'POST');
  el('secret').value = key.secret; el('secret-box').hidden = false;
  el('message').textContent = 'Key generated. Copy it before leaving this page.';
  await refresh();
}));
el('hide-key').addEventListener('click', clearSecret);
el('revoke').addEventListener('click', () => { el('confirm').open = true; el('confirm-revoke').focus(); });
el('confirm-revoke').addEventListener('click', () => action(async () => {
  await request('/account/api/key', 'DELETE'); clearSecret(); await refresh();
  el('message').textContent = 'Key revoked. It can no longer call the API.';
}));
el('refresh').addEventListener('click', () => action(refresh));
el('logout').addEventListener('click', () => action(async () => {
  await request('/account/logout', 'POST'); signedOut(); el('message').textContent = 'Signed out.';
}));
window.addEventListener('pagehide', clearSecret);
window.addEventListener('pageshow', event => { if (event.persisted) { signedOut(); action(refresh); } });
el('example').textContent = `curl '${location.origin}/v1/passes?noradId=25544&lat=45.75&lon=4.85' \\\n  -H 'X-API-Key: YOUR_API_KEY'`;
action(async () => {
  try { await refresh(); } catch (error) {
    if (new URLSearchParams(location.search).get('login') === 'failed') throw new Error('GitHub sign-in failed. Please try again.');
    throw error;
  }
});
