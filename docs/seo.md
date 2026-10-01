# Search engines and languages

What makes the site readable by a search engine, in English and in French, and what is
still missing. The short version: the technical blockers are gone; ranking now depends on
content and on links from other sites, which no code change provides.

## What a crawler gets

**Prerendered HTML.** `ng build` writes every page to HTML at build time
(`outputMode: "static"`, routes in `frontend/src/app/app.routes.server.ts`): the static
pages, and one page per featured satellite (`frontend/src/app/shared/featured.ts`). The
HTML carries the title, the description, the headings and the text before any script
runs. The browser then *hydrates* it - adopts the existing DOM rather than drawing it
again (`provideClientHydration(withEventReplay(), withI18nSupport())`; without
`withI18nSupport` every translated component would be skipped and redrawn).

Passes are never prerendered: a pass computed at build time is stale by the time anyone
reads it. On the server the satellite page makes no request (`isPlatformBrowser`); its
prerendered HTML holds the name, the description and the links, and the browser computes
the passes. A catalogue number that is not featured (`/satellites/43013`) is not
prerendered: it gets `index.csr.html`, the shell that renders in the browser.

**Two languages, two builds.** `@angular/localize` builds English at the root - every
address published before the translation still works - and French under `/fr/`
(`angular.json`, `i18n`). The language switch in the header and the footer is a plain link
to the same page in the other build, never a router navigation. Visitors are not
redirected by `Accept-Language`: Google crawls from the United States without that
header, and a forced redirect would hide the French pages from it.

**Head tags** (`frontend/src/app/shared/seo.ts`), set on every navigation and therefore in
the prerendered HTML:

| Tag | Content |
|---|---|
| `<title>`, `description` | Per route, translated; per featured satellite, its name and blurb |
| `link rel=canonical` | The page in its own language, without query string |
| `link rel=alternate hreflang` | `en`, `fr`, and `x-default` → English |
| Open Graph, `twitter:card` | Title, description, URL, locale and alternate locale |
| `robots: noindex` | The not-found page only, which a static host answers with 200 |
| JSON-LD | `WebApplication` on the home page |

**robots.txt and sitemap.xml** are written after the build by
`frontend/scripts/build.mjs`, from `dist/frontend/prerendered-routes.json`: one `<url>` per
page and language, each listing its translations.

## The public address

Canonical links, hreflang and the sitemap need the absolute public origin. It is decided
where the build runs, in this order:

1. `SITE_URL` (for instance `https://nextpass.space`), if set in the build environment;
2. `https://` + `VERCEL_PROJECT_PRODUCTION_URL`, which Vercel sets on every build to the
   project's production domain (its shortest custom domain, else its `*.vercel.app`);
3. nothing: the build succeeds, canonical and hreflang links and the sitemap are left out.

A wrong canonical is worse than none, so there is no hard-coded fallback. Preview
deployments carry the production canonical, which is right: they are copies.

The production domain is `nextpass.space`. Its apex must be the domain Vercel serves, with
`www` redirecting to it, not the reverse: `VERCEL_PROJECT_PRODUCTION_URL` picks the
shortest custom domain, so a project that redirects the apex to `www` would publish
canonical links that themselves redirect. Either keep the apex primary in Vercel's
domain settings or set `SITE_URL=https://www.nextpass.space` explicitly.

## Adding or changing text

Any text in a template carries `i18n`; any text in TypeScript is a `` $localize`…` ``
string. Then:

```bash
cd frontend
npm run i18n        # extract, then compare with src/locale/messages.fr.json
```

It lists missing translations, translations whose placeholders differ, and unused ones.
The build refuses a missing translation (`i18nMissingTranslation: "error"`), so an
untranslated string cannot be deployed. In development, `npm start` serves English and
`npx ng serve --configuration fr` serves French - at the root of the dev server, not
under `/fr/`. The dev server renders parameterised pages (`/satellites/:noradId`) in the
browser; only `npm run build` prerenders them.

What stays English on purpose: the API's error messages (`ProblemDetail` from the
backend), CSV column names, route paths (`/fr/satellites`, not `/fr/satellites-a-voir`),
and code samples.

## After deploying - checks no test can make

- Open `/satellites/25544` and `/fr/satellites/25544` on the deployment and view the
  source: the title and the text must be there. If Vercel answers the CSR shell instead,
  its directory-index resolution differs from the local check, and the rewrites in
  `vercel.json` need the explicit page paths.
- `curl -s https://<domain>/robots.txt` and `/sitemap.xml`: the sitemap must name the
  production domain, not a preview one.
- Declare the domain in Google Search Console and Bing Webmaster Tools, submit
  `/sitemap.xml`, and inspect one page per language with the URL inspection tool.
- Lighthouse's SEO audit on one page of each language.

## What is not done, by order of value

1. **Search Console on the new domain.** `nextpass.space` replaced the `*.vercel.app`
   address before the site earned links. Verify the domain property, submit
   `https://nextpass.space/sitemap.xml`, and redirect the old address to it in Vercel's
   domain settings: it still answers 200 with the same pages, which is duplicate content.
2. **Pages that answer real searches.** "ISS pass tonight Paris", "voir l'ISS ce soir
   Lyon": a page per city is the long tail, but only with content unique to the city (its
   next visible passes, its local times). Thousands of near-identical pages are what
   Google's scaled-content policy penalises.
3. **More prerendered satellites.** Only the ten featured ones are; Starlink trains, NOAA
   weather satellites and the CubeSats radio amateurs track are each a page people search
   for.
4. **An Open Graph image** (1200×630): links shared on social networks show none today.
5. **Links from other sites**: astronomy clubs, AMSAT and amateur-radio forums, Show HN
   for the API - see milestone 17 of the [roadmap](../ROADMAP.md). No setting replaces
   them.
6. **The page heading.** Every page's `<h1>` is the brand in the header; the page's own
   title is an `<h2>`. A minor signal, changed with the shared styles.
