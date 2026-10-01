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
| `og:image`, `twitter:image` | `public/og/en.png` or `fr.png`, 1200×630, with `summary_large_image`; left out without a known origin, since the address must be absolute |
| `robots: noindex` | The not-found page only, which a static host answers with 200 |
| JSON-LD | `WebApplication` on the home page; `BreadcrumbList` (NextPass › Satellites › …) on `/starlink` and the featured satellites' pages, which need an absolute origin |
| Headings | One `<h1>` per page: its own title. The brand in the header is not a heading |

**robots.txt and sitemap.xml** are written after the build by
`frontend/scripts/build.mjs`, from `dist/frontend/prerendered-routes.json`: one `<url>` per
page and language, each listing its translations.

## The public address

Canonical links, hreflang and the sitemap need the absolute public origin. It is decided
where the build runs, in this order:

1. `SITE_URL` (for instance `https://www.nextpass.space`), if set in the build environment;
2. `https://` + `VERCEL_PROJECT_PRODUCTION_URL`, which Vercel sets on every build to the
   project's production domain (its shortest custom domain, else its `*.vercel.app`);
3. nothing: the build succeeds, canonical and hreflang links and the sitemap are left out.

A wrong canonical is worse than none, so there is no hard-coded fallback. Preview
deployments carry the production canonical, which is right: they are copies.

The production domain is `www.nextpass.space`: Vercel serves `www`, and the apex
`nextpass.space` answers 308 to it (checked on 1 October 2026). The canonical links,
hreflang and the sitemap all name `www`, which is consistent. What matters is that they
name the domain that answers 200, never the one that redirects: if the apex is ever made
primary in Vercel's domain settings, the next build follows it through
`VERCEL_PROJECT_PRODUCTION_URL`, unless `SITE_URL` pins it. Anything else that names the
site - `TLE_BASE_URLS` on Render, the account page's links - should use `www` too, or it
pays one redirect per call.

The Search Console property is best a *Domain* property (DNS TXT record): it covers both
hosts. A URL-prefix property for `https://nextpass.space` would refuse the sitemap, whose
addresses are on `www`.

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

## Advertising (Google AdSense)

`src/index.html` loads the AdSense script with **Auto ads**: Google decides where ads go,
so the templates hold no `<ins class="adsbygoogle">` slot. `public/ads.txt` authorises the
publisher id `pub-7308548223772082` and must answer at `https://www.nextpass.space/ads.txt`.

The code cannot collect consent. Before the script reaches production, enable the GDPR
message (EEA, UK, Switzerland) under *Privacy & messaging* in the AdSense console: it is
Google's certified consent platform, served by the same script. Without it, French
visitors get advertising cookies without consent, which the privacy page now says they
will not. Auto ads scans a page when it loads; the router's later navigations do not
re-run it. The API account page is served by the backend and never loads the script;
`/legal` and `/fr/legal` are worth excluding in the Auto ads settings.

## The Starlink page

`/starlink` and `/fr/starlink` answer the searches that follow every Starlink launch ("line
of lights in the sky", "train Starlink ce soir"). The explanation is prerendered; the
browser asks `GET /api/satellites/launches?q=starlink&limit=3` for the newest launches -
active satellites grouped by the launch part of their international designator - then the
passes of the lowest-numbered satellite of the chosen launch, which stands for the train
while it is compact. A satellite is in CelesTrak's active group only once catalogued,
usually a day or more after launch, so the first evening of a train is often missing; the
page says so. The page is worth sharing the day after a launch, not later: that is when
people search.

## What is not done, by order of value

1. **Search Console on the domain.** Verify a Domain property for `nextpass.space` (it
   covers `www`) and submit `https://www.nextpass.space/sitemap.xml`. The old
   `*.vercel.app` addresses answer 404 since the move, so there is no duplicate left.
2. **Pages that answer real searches.** "ISS pass tonight Paris", "voir l'ISS ce soir
   Lyon": a page per city is the long tail, but only with content unique to the city (its
   next visible passes, its local times). Passes are computed in the browser today, so the
   HTML of two city pages would differ by the name alone - exactly what Google's
   scaled-content policy penalises. Such pages need their passes in the HTML, for instance
   from a daily rebuild (a Vercel deploy hook called on a schedule).
3. **More prerendered satellites.** The seventeen featured ones are (`shared/featured.ts`,
   numbers checked against CelesTrak on 1 October 2026). A featured satellite's meta
   description is its blurb plus a fixed sentence: keep the blurb under about 95
   characters in both languages, which a test checks in English only.
4. **Links from other sites**: astronomy clubs, AMSAT and amateur-radio forums, Show HN
   for the API - see milestone 17 of the [roadmap](../ROADMAP.md). No setting replaces
   them.
