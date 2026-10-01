import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pageUrl, robotsTxt, siteOrigin, sitemapXml, splitLanguage } from './seo-files.mjs';

test('the origin comes from SITE_URL first, then from Vercel, else is empty', () => {
  assert.equal(siteOrigin({ SITE_URL: 'https://satpass.example/', VERCEL_PROJECT_PRODUCTION_URL: 'x.vercel.app' }), 'https://satpass.example');
  assert.equal(siteOrigin({ VERCEL_PROJECT_PRODUCTION_URL: 'sat-pass.vercel.app' }), 'https://sat-pass.vercel.app');
  assert.equal(siteOrigin({}), '');
});

test('a route is split into its language and the path the router sees', () => {
  assert.deepEqual(splitLanguage('/'), { language: 'en', path: '/' });
  assert.deepEqual(splitLanguage('/fr'), { language: 'fr', path: '/' });
  assert.deepEqual(splitLanguage('/fr/satellites/25544'), { language: 'fr', path: '/satellites/25544' });
  assert.deepEqual(splitLanguage('/francais'), { language: 'en', path: '/francais' });
});

test('page addresses match the canonical links of the app', () => {
  assert.equal(pageUrl('https://s.example', 'en', '/'), 'https://s.example/');
  assert.equal(pageUrl('https://s.example', 'fr', '/'), 'https://s.example/fr/');
  assert.equal(pageUrl('https://s.example', 'fr', '/satellites'), 'https://s.example/fr/satellites');
});

test('the sitemap lists each page in both languages, each pointing at the other', () => {
  const xml = sitemapXml('https://s.example', ['/', '/fr', '/satellites', '/fr/satellites']);
  assert.equal((xml.match(/<url>/g) ?? []).length, 4);
  assert.match(xml, /<loc>https:\/\/s\.example\/fr\/satellites<\/loc>/);
  assert.match(xml, /hreflang="x-default" href="https:\/\/s\.example\/satellites"/);
  assert.match(xml, /hreflang="fr" href="https:\/\/s\.example\/fr\/"/);
});

test('robots.txt announces the sitemap only when the origin is known', () => {
  assert.match(robotsTxt('https://s.example'), /Sitemap: https:\/\/s\.example\/sitemap\.xml/);
  assert.doesNotMatch(robotsTxt(''), /Sitemap/);
});
