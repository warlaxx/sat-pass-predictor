/**
 * `npm run build`: the Angular build, plus the files that depend on the public address.
 *
 * The address is known only where the build runs - Vercel names its production domain in
 * VERCEL_PROJECT_PRODUCTION_URL, and SITE_URL overrides it. It goes into the bundle as
 * `ngSiteOrigin` (canonical and hreflang links, see src/app/shared/seo.ts), and into
 * robots.txt and sitemap.xml. Without one, the build still succeeds: the links and the
 * sitemap are left out rather than pointing at the wrong host.
 *
 * Before building, it fetches the newest separation events (separations-snapshot.mjs) and
 * hands the file to the prerender through NG_SEPARATIONS_SNAPSHOT: those pages are written
 * to HTML, and so listed in the sitemap.
 */
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { robotsTxt, siteOrigin, sitemapXml } from './seo-files.mjs';
import { fetchSnapshot, snapshotApi } from './separations-snapshot.mjs';
import { previewPath, renderPreview } from './og-images.mjs';

const root = new URL('../', import.meta.url);
const ng = createRequire(import.meta.url).resolve('@angular/cli/bin/ng.js');
const origin = siteOrigin(process.env);

console.log(origin ? `Public origin: ${origin}` : 'No SITE_URL or VERCEL_PROJECT_PRODUCTION_URL: canonical links and sitemap left out.');

const api = snapshotApi(process.env);
const separations = api ? await fetchSnapshot(api) : {};
console.log(api
  ? `Separations to prerender, from ${api}: ${Object.keys(separations).length}.`
  : 'PRERENDER_API=none: no separation page prerendered.');
const snapshot = new URL('tmp/separations.json', root);
mkdirSync(new URL('./', snapshot), { recursive: true });
writeFileSync(snapshot, JSON.stringify(separations));

const build = spawnSync(process.execPath, [ng, 'build', '--define', `ngSiteOrigin=${JSON.stringify(origin)}`, ...process.argv.slice(2)], {
  cwd: root, stdio: 'inherit', env: { ...process.env, NG_SEPARATIONS_SNAPSHOT: fileURLToPath(snapshot) },
});
if (build.status !== 0) process.exit(build.status ?? 1);

const output = new URL('dist/frontend/', root);
const browser = new URL('browser/', output);
const { routes } = JSON.parse(readFileSync(new URL('prerendered-routes.json', output), 'utf8'));

// ABD-33: a link preview per prerendered event and language, where its page names it.
const previews = new URL('og/separations/', browser);
mkdirSync(previews, { recursive: true });
for (const event of Object.values(separations)) {
  for (const language of ['en', 'fr']) {
    writeFileSync(new URL(`.${previewPath(event.id, language)}`, browser), await renderPreview(event, language));
  }
}
console.log(`Link previews: ${Object.keys(separations).length * 2} images.`);

writeFileSync(new URL('robots.txt', browser), robotsTxt(origin));
const sitemap = new URL('sitemap.xml', browser);
if (origin) {
  writeFileSync(sitemap, sitemapXml(origin, Object.keys(routes)));
  console.log(`sitemap.xml: ${Object.keys(routes).length} pages.`);
} else {
  rmSync(sitemap, { force: true });
}
