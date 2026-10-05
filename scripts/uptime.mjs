/**
 * The uptime probe run by .github/workflows/uptime.yml (ABD-29).
 *
 * Checks the website and the API, and keeps one GitHub issue labelled `incident` in step
 * with the result: opened when a check is down, closed when every check it names answers
 * again. The issue is the alert (GitHub notifies the owner by e-mail and on mobile) and,
 * the repository being public, the outage history the status page links to.
 *
 * A check is down only after two consecutive failures, a minute apart, inside the same
 * run: one timeout is a blip, two are an outage. A cold start of the free Render instance
 * (about thirty seconds) fits in the first attempt's 60 s.
 *
 *   node scripts/uptime.mjs [fast|deep]
 *
 * `deep` also checks what reads PostgreSQL; `fast` leaves the database alone (see the
 * workflow for why the two run at different rates). Without GITHUB_TOKEN and
 * GITHUB_REPOSITORY, it only prints the results.
 */
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const INCIDENT_LABEL = 'incident';
export const INCIDENT_TITLE = 'Outage: a NextPass check is failing';
const MARKER = /<!-- uptime-checks: ([a-z,-]*) -->/;

export function checks(siteUrl, backendUrl) {
  const isUp = (body) => JSON.parse(body).status === 'UP';
  return [
    { id: 'website', name: 'Website', url: `${siteUrl}/`, deep: false, valid: (body) => body.includes('<app-root') },
    { id: 'backend', name: 'Backend (liveness)', url: `${backendUrl}/actuator/health/liveness`, deep: false, valid: isUp },
    { id: 'health', name: 'Backend health, with the database', url: `${backendUrl}/actuator/health`, deep: true, valid: isUp },
    {
      id: 'separations', name: 'Separations API, through the website', url: `${siteUrl}/api/separations`, deep: true,
      valid: (body) => Array.isArray(JSON.parse(body).events),
    },
  ];
}

/** One request: up means a 2xx answer within the timeout whose body looks right. */
export async function probe(check, { fetchImpl = fetch, timeoutMs = 60_000 } = {}) {
  const start = Date.now();
  try {
    const response = await fetchImpl(check.url, { signal: AbortSignal.timeout(timeoutMs), headers: { 'User-Agent': 'nextpass-uptime' } });
    const body = await response.text();
    const millis = Date.now() - start;
    if (!response.ok) return { up: false, millis, detail: `HTTP ${response.status}` };
    let valid = false;
    try { valid = check.valid(body); } catch { /* not the JSON expected */ }
    return valid ? { up: true, millis, detail: `HTTP ${response.status}` } : { up: false, millis, detail: `HTTP ${response.status}, unexpected body` };
  } catch (error) {
    return { up: false, millis: Date.now() - start, detail: error.name === 'TimeoutError' ? `no answer within ${timeoutMs / 1000} s` : error.message };
  }
}

/** Down only if the retry fails too. */
export async function confirm(check, { retryAfterMs = 60_000, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), ...options } = {}) {
  const first = await probe(check, options);
  if (first.up) return { ...check, ...first };
  await sleep(retryAfterMs);
  const second = await probe(check, options);
  return { ...check, ...second, detail: second.up ? `${second.detail} (first attempt: ${first.detail})` : `${first.detail}, then ${second.detail}` };
}

export function checkedIds(issue) {
  return issue?.body?.match(MARKER)?.[1].split(',').filter(Boolean) ?? [];
}

/**
 * What to do with the incident issue, given this run's results and the open issue if any.
 * Pure, so the rules are tested without GitHub: open on a new outage, comment when another
 * check joins it, close only once every check the issue names has been seen up again - a
 * fast run cannot close an outage of the database, which it did not look at.
 */
export function decide(results, issue) {
  const down = results.filter((r) => !r.up).map((r) => r.id);
  if (!issue) return down.length ? { action: 'open', ids: down } : { action: 'none' };
  const known = checkedIds(issue);
  if (down.length) {
    const added = down.filter((id) => !known.includes(id));
    return added.length ? { action: 'comment', ids: [...known, ...added], added } : { action: 'none' };
  }
  const seenUp = new Set(results.map((r) => r.id));
  return known.every((id) => seenUp.has(id)) ? { action: 'close' } : { action: 'none' };
}

export function table(results) {
  return [
    '| Check | State | Time | Detail |',
    '| --- | --- | --- | --- |',
    ...results.map((r) => `| [${r.name}](${r.url}) | ${r.up ? 'up' : '**down**'} | ${r.millis} ms | ${r.detail} |`),
  ].join('\n');
}

export function issueBody(ids, results, { runUrl, owner }) {
  return [
    `<!-- uptime-checks: ${ids.join(',')} -->`,
    `${owner ? `@${owner} ` : ''}A check failed twice in a row, a minute apart. This issue closes itself once every check below answers again.`,
    '',
    table(results),
    '',
    `Run: ${runUrl}`,
  ].join('\n');
}

async function github(path, { method = 'GET', body } = {}) {
  const response = await fetch(`https://api.github.com/repos/${process.env.GITHUB_REPOSITORY}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${process.env.GITHUB_TOKEN}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
    },
    body: body && JSON.stringify(body),
  });
  // 422 on creating the label: it already exists.
  if (!response.ok && !(method === 'POST' && path === '/labels' && response.status === 422)) {
    throw new Error(`GitHub ${method} ${path}: HTTP ${response.status} ${await response.text()}`);
  }
  return response.status === 204 ? undefined : response.json();
}

async function main() {
  const mode = process.argv[2] === 'deep' ? 'deep' : 'fast';
  const siteUrl = (process.env.SITE_URL || 'https://www.nextpass.space').replace(/\/$/, '');
  const backendUrl = (process.env.BACKEND_URL || 'https://sat-pass-predictor-api.onrender.com').replace(/\/$/, '');
  const selected = checks(siteUrl, backendUrl).filter((c) => mode === 'deep' || !c.deep);
  const results = await Promise.all(selected.map((c) => confirm(c)));

  const summary = `## Uptime (${mode})\n\n${table(results)}\n`;
  console.log(summary);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);

  if (process.env.GITHUB_TOKEN && process.env.GITHUB_REPOSITORY) {
    // The REST list, not the search API: search lags behind, and would open a duplicate.
    const open = await github(`/issues?labels=${INCIDENT_LABEL}&state=open&per_page=100`);
    const issue = open.find((i) => i.title === INCIDENT_TITLE && !i.pull_request);
    const runUrl = `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}`;
    const decision = decide(results, issue);
    if (decision.action === 'open') {
      await github('/labels', { method: 'POST', body: { name: INCIDENT_LABEL, color: 'B60205', description: 'Outage raised by a monitoring workflow' } });
      const created = await github('/issues', {
        method: 'POST',
        body: { title: INCIDENT_TITLE, labels: [INCIDENT_LABEL], body: issueBody(decision.ids, results, { runUrl, owner: process.env.GITHUB_REPOSITORY_OWNER }) },
      });
      console.log(`Opened ${created.html_url}`);
    } else if (decision.action === 'comment') {
      // An issue whose marker was edited away gets it back, or every run would comment again.
      const marker = `<!-- uptime-checks: ${decision.ids.join(',')} -->`;
      const body = MARKER.test(issue.body ?? '') ? issue.body.replace(MARKER, marker) : `${marker}\n${issue.body ?? ''}`;
      await github(`/issues/${issue.number}`, { method: 'PATCH', body: { body } });
      await github(`/issues/${issue.number}/comments`, { method: 'POST', body: { body: `Now down as well: ${decision.added.join(', ')}.\n\n${table(results)}\n\nRun: ${runUrl}` } });
      console.log(`Updated ${issue.html_url}`);
    } else if (decision.action === 'close') {
      const minutes = Math.round((Date.now() - Date.parse(issue.created_at)) / 60_000);
      await github(`/issues/${issue.number}/comments`, { method: 'POST', body: { body: `Every check answers again, about ${minutes} min after this issue was opened.\n\n${table(results)}\n\nRun: ${runUrl}` } });
      await github(`/issues/${issue.number}`, { method: 'PATCH', body: { state: 'closed', state_reason: 'completed' } });
      console.log(`Closed ${issue.html_url}`);
    }
  }

  // A red run in the public history for every run that found something down.
  if (results.some((r) => !r.up)) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
