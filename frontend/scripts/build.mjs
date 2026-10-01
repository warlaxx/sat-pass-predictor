/**
 * `npm run build`: the Angular build, plus the files that depend on the public address.
 *
 * The address is known only where the build runs - Vercel names its production domain in
 * VERCEL_PROJECT_PRODUCTION_URL, and SITE_URL overrides it. It goes into the bundle as
 * `ngSiteOrigin` (canonical and hreflang links, see src/app/shared/seo.ts), and into
 * robots.txt and sitemap.xml. Without one, the build still succeeds: the links and the
 * sitemap are left out rather than pointing at the wrong host.
 */
import { spawnSync } from 'node:child_process';
import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { robotsTxt, siteOrigin, sitemapXml } from './seo-files.mjs';

const root = new URL('../', import.meta.url);
const ng = createRequire(import.meta.url).resolve('@angular/cli/bin/ng.js');
const origin = siteOrigin(process.env);

console.log(origin ? `Public origin: ${origin}` : 'No SITE_URL or VERCEL_PROJECT_PRODUCTION_URL: canonical links and sitemap left out.');

const build = spawnSync(process.execPath, [ng, 'build', '--define', `ngSiteOrigin=${JSON.stringify(origin)}`, ...process.argv.slice(2)], {
  cwd: root, stdio: 'inherit',
});
if (build.status !== 0) process.exit(build.status ?? 1);

const output = new URL('dist/frontend/', root);
const browser = new URL('browser/', output);
const { routes } = JSON.parse(readFileSync(new URL('prerendered-routes.json', output), 'utf8'));

writeFileSync(new URL('robots.txt', browser), robotsTxt(origin));
const sitemap = new URL('sitemap.xml', browser);
if (origin) {
  writeFileSync(sitemap, sitemapXml(origin, Object.keys(routes)));
  console.log(`sitemap.xml: ${Object.keys(routes).length} pages.`);
} else {
  rmSync(sitemap, { force: true });
}
