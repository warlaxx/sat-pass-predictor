/**
 * One 1200×630 link preview per prerendered separation and language (ABD-33): what X,
 * Bluesky, LinkedIn or Reddit show when an event page is shared, instead of the site's
 * single image.
 *
 * The words come from the build's snapshot of the events, the same one the pages are
 * prerendered from, and say what the page's heading says. Satori lays the card out as an
 * SVG with its text drawn as paths (Public Sans from the site's own fonts), resvg turns it
 * into a PNG: no browser at build time, so it runs where Vercel builds.
 *
 * The layout and the words are pure functions, so node --test can check them
 * (og-images.test.mjs); build.mjs renders the files once the pages exist.
 */
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

export const WIDTH = 1200;
export const HEIGHT = 630;

/** The address of an event's preview, as the page names it in og:image. */
export function previewPath(id, language) {
  return `/og/separations/${encodeURIComponent(id)}.${language}.png`;
}

const WORDS = {
  en: {
    release: 'Release',
    fragmentation: 'Fragmentation',
    released: (parent, child) => `${parent} released ${child}`,
    releasedMany: (parent, count) => `${parent} released ${count} objects`,
    fragment: (parent) => `A fragment separated from ${parent}`,
    fragments: (parent, count) => `${count} fragments separated from ${parent}`,
    footer: 'Separations in orbit, with the record behind each one',
    locale: 'en-GB',
  },
  fr: {
    release: 'Libération',
    fragmentation: 'Fragmentation',
    released: (parent, child) => `${parent} a libéré ${child}`,
    releasedMany: (parent, count) => `${parent} a libéré ${count} objets`,
    fragment: (parent) => `Un fragment s’est détaché de ${parent}`,
    fragments: (parent, count) => `${count} fragments se sont détachés de ${parent}`,
    footer: 'Les séparations en orbite, avec la fiche de chacune',
    locale: 'fr-FR',
  },
};

/** The page's heading, in the same words (separation.ts). */
export function eventHeading(event, language) {
  const words = WORDS[language];
  const record = event.children[0]?.evidence?.parent ?? null;
  const parent = event.parent?.name ?? (record ? record.trim().split(/\s+/)[0].replace(/\*$/, '') : '?');
  if (event.kind === 'FRAGMENTATION') {
    return event.childCount === 1 ? words.fragment(parent) : words.fragments(parent, event.childCount);
  }
  const child = event.childCount === 1 ? event.children[0] : undefined;
  return child ? words.released(parent, child.name ?? child.id) : words.releasedMany(parent, event.childCount);
}

/** The date with the precision it was recorded with, never more ("September 2026"). */
export function eventDate(date, language) {
  if (!date?.at || !date.precision) return date?.text ?? '';
  const at = new Date(date.at);
  const options = { timeZone: 'UTC' };
  switch (date.precision) {
    case 'DECADE': return `${Math.floor(at.getUTCFullYear() / 10) * 10}s`;
    case 'YEAR': return String(at.getUTCFullYear());
    case 'QUARTER': return `Q${Math.floor(at.getUTCMonth() / 3) + 1} ${at.getUTCFullYear()}`;
    case 'MONTH': return new Intl.DateTimeFormat(WORDS[language].locale, { ...options, month: 'long', year: 'numeric' }).format(at);
    default: return new Intl.DateTimeFormat(WORDS[language].locale, { ...options, day: 'numeric', month: 'long', year: 'numeric' }).format(at);
  }
}

const el = (type, style, children) => ({ type, props: { style, children } });

/** The card, as the element tree satori lays out: kind, heading, date, site. */
export function previewCard(event, language) {
  const words = WORDS[language];
  const fragmentation = event.kind === 'FRAGMENTATION';
  const heading = eventHeading(event, language);
  return el('div', {
    width: WIDTH, height: HEIGHT, display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
    padding: '64px 72px', background: '#000000', color: '#ffffff', fontFamily: 'Public Sans',
    backgroundImage: 'radial-gradient(circle at 85% 20%, #1a1f3d 0%, #000000 55%)',
  }, [
    el('div', { display: 'flex', alignItems: 'center', gap: 16 }, [
      el('div', {
        display: 'flex', border: `2px solid ${fragmentation ? '#6b3a2e' : '#3a4280'}`, borderRadius: 999,
        color: fragmentation ? '#ff8a6b' : '#96a4ff', fontSize: 26, fontWeight: 600, letterSpacing: 2,
        padding: '6px 22px', textTransform: 'uppercase',
      }, fragmentation ? words.fragmentation : words.release),
      el('div', { display: 'flex', color: '#9295a0', fontSize: 28 }, eventDate(event.date, language)),
    ]),
    el('div', {
      display: 'flex', fontSize: heading.length > 60 ? 64 : 80, fontWeight: 600, letterSpacing: -2, lineHeight: 1.05,
    }, heading),
    el('div', { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', color: '#c4c6cf', fontSize: 26 }, [
      el('div', { display: 'flex' }, words.footer),
      el('div', { display: 'flex', color: '#ffffff', fontSize: 34, fontWeight: 600 }, 'NextPass'),
    ]),
  ]);
}

/** Renders one card to PNG bytes. Loaded lazily: the tests of the words need neither. */
export async function renderPreview(event, language) {
  const require = createRequire(import.meta.url);
  const { default: satori } = await import('satori');
  const { Resvg } = await import('@resvg/resvg-js');
  const font = (weight) => readFileSync(require.resolve(`@fontsource/public-sans/files/public-sans-latin-${weight}-normal.woff`));
  const fonts = [
    { name: 'Public Sans', data: font(400), weight: 400, style: 'normal' },
    { name: 'Public Sans', data: font(600), weight: 600, style: 'normal' },
  ];
  const svg = await satori(previewCard(event, language), { width: WIDTH, height: HEIGHT, fonts });
  return new Resvg(svg, { fitTo: { mode: 'width', value: WIDTH } }).render().asPng();
}
