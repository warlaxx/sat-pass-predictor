import { SiteLanguage, languageOf } from '../../shared/locale';
import data from './iss-cities.json';

/**
 * The cities with a page of their own, /iss/:slug (ABD-34): the searches "ISS tonight
 * Paris" and "voir l'ISS ce soir Lyon" land on the next passes over that city.
 *
 * The coordinates are the backend's too (backend/src/main/resources/iss-cities.json):
 * `/api/featured-pass?city=` computes only these places, and FeaturedCitiesTest checks
 * that both lists agree.
 */
export interface IssCity {
  readonly slug: string;
  readonly lat: number;
  readonly lon: number;
  readonly alt: number;
  readonly en: string;
  readonly fr: string;
  /** ISO 3166-1 alpha-2. */
  readonly country: string;
  /** "de Paris", "d'Édimbourg", "du Cap": French elides and contracts "de" before a name. */
  readonly frOf: string;
}

export const ISS_CITIES: readonly IssCity[] = data;

/** The inclination of the ISS's orbit: its ground track never leaves ±51.6°. */
export const ISS_INCLINATION_DEG = 51.64;
const EARTH_RADIUS_KM = 6371;
const ISS_ALTITUDE_KM = 420;

export function issCity(slug: string | null | undefined): IssCity | undefined {
  return ISS_CITIES.find((city) => city.slug === slug);
}

/**
 * The city's name in the build's language. From `$localize.locale`, not LOCALE_ID: the
 * route's title and description are built outside an injection context.
 */
export function cityName(city: IssCity, language: SiteLanguage = languageOf($localize.locale ?? 'en')): string {
  return city[language];
}

/**
 * How high the ISS can climb over a latitude: overhead where the ground track reaches,
 * lower beyond its edge, from the angle between the city and the edge of the track.
 */
export function highestElevationDeg(latitudeDeg: number): number {
  const beyond = Math.abs(latitudeDeg) - ISS_INCLINATION_DEG;
  if (beyond <= 0) return 90;
  const theta = (beyond * Math.PI) / 180;
  const ratio = EARTH_RADIUS_KM / (EARTH_RADIUS_KM + ISS_ALTITUDE_KM);
  return (Math.atan2(Math.cos(theta) - ratio, Math.sin(theta)) * 180) / Math.PI;
}

/** Where a city stands relative to the ISS's track, which decides what its passes are like. */
export type TrackBand = 'tropics' | 'under' | 'edge' | 'beyond';

export function trackBand(latitudeDeg: number): TrackBand {
  const lat = Math.abs(latitudeDeg);
  if (lat < 23.44) return 'tropics';
  if (lat <= ISS_INCLINATION_DEG - 3) return 'under';
  if (lat <= ISS_INCLINATION_DEG) return 'edge';
  return 'beyond';
}

/** "48.9° N": one decimal, a hemisphere letter, as an atlas writes it. */
export function latitudeLabel(latitudeDeg: number, decimals = 1): string {
  const hemisphere = latitudeDeg >= 0
    ? $localize`:Northern hemisphere, after a latitude:N`
    : $localize`:Southern hemisphere, after a latitude:S`;
  return `${Math.abs(latitudeDeg).toFixed(decimals)}° ${hemisphere}`;
}

/**
 * The name where English says "over Paris" and French "au-dessus de Paris": in French,
 * the "de" comes with the name, elided or contracted ("d'Édimbourg", "du Cap"), and the
 * translations leave it out.
 */
export function cityOf(city: IssCity, language: SiteLanguage = languageOf($localize.locale ?? 'en')): string {
  return language === 'fr' ? city.frOf : city.en;
}

export function cityHeading(city: IssCity): string {
  return $localize`:Heading of an ISS city page:When to see the ISS over ${cityOf(city)}:city:`;
}

export function cityTitle(city: IssCity): string {
  return $localize`:Page title of an ISS city page:ISS over ${cityOf(city)}:city: tonight: next visible passes`;
}

export function cityDescription(city: IssCity): string {
  return $localize`:Meta description of an ISS city page:When the International Space Station passes over ${cityOf(city)}:city: in the next two days: times, direction and how high it climbs, computed live.`;
}

/**
 * What passes are like over this city, from where it stands relative to the track. The
 * numbers - its latitude, how high the station can climb - differ from city to city, so
 * no two pages say the same thing with only the name changed.
 */
export function cityIntro(city: IssCity): string {
  return `${trackIntro(city)} ${$localize`:Where the passes of an ISS city page are computed:The passes below are computed for ${coordinatesLabel(city)}:coordinates:, ${Math.round(city.alt)}:altitude: m above sea level.`}`;
}

/** "48.8566° N, 2.3522° E": the point the passes are computed for. */
export function coordinatesLabel(city: IssCity): string {
  const east = city.lon >= 0
    ? $localize`:Eastern longitude, after a longitude:E`
    : $localize`:Western longitude, after a longitude:W`;
  return `${latitudeLabel(city.lat, 4)}, ${Math.abs(city.lon).toFixed(4)}° ${east}`;
}

function trackIntro(city: IssCity): string {
  const name = cityName(city);
  const latitude = latitudeLabel(city.lat);
  switch (trackBand(city.lat)) {
    case 'tropics':
      return $localize`:Intro of an ISS city page in the tropics:${name}:city: lies at ${latitude}:latitude:, well inside the band the ISS flies over. Its passes can climb overhead and come in short series, but tropical twilight is brief: the window to see the station after sunset or before sunrise is short.`;
    case 'under':
      return $localize`:Intro of an ISS city page under the track:At ${latitude}:latitude:, ${name}:city: sits under the track of the ISS: on its best passes the station climbs close to the zenith. Visible passes come in series of one to two weeks, every month or two, when its orbit lines up with dusk or dawn.`;
    case 'edge':
      return $localize`:Intro of an ISS city page near the edge of the track:${name}:city:, at ${latitude}:latitude:, is near the northern edge of the ISS's track, which turns at 51.6°. The station runs along that edge in long passes, and around the June solstice it can stay in sunlight all night, giving several visible passes in a row.`;
    case 'beyond': {
      const peak = Math.round(highestElevationDeg(city.lat));
      return $localize`:Intro of an ISS city page beyond the track:${name}:city: is at ${latitude}:latitude:, north of the ISS's track, which turns at 51.6°. The station never passes overhead here: its best passes peak at about ${peak}:peak:° above the southern horizon, and around the June solstice several come in a row on bright nights.`;
    }
  }
}

export interface Faq {
  readonly question: string;
  readonly answer: string;
}

/** The questions the page answers in words, and in its FAQPage structured data. */
export function cityFaq(city: IssCity): readonly Faq[] {
  const name = cityName(city);
  const low = trackBand(city.lat) === 'beyond';
  return [
    {
      question: $localize`:FAQ of an ISS city page:When can I see the ISS from ${name}:city:?`,
      answer: $localize`:FAQ of an ISS city page:When it passes in sunlight while the sky over ${cityOf(city)}:city: is dark: in the two hours after sunset or before sunrise, in series that last one to two weeks. This page lists the next passes, computed as it opens.`,
    },
    {
      question: $localize`:FAQ of an ISS city page:Can you see the ISS with the naked eye from ${name}:city:?`,
      answer: low
        ? $localize`:FAQ of an ISS city page, beyond the track:Yes. On a good pass it is brighter than any star: a steady light crossing the sky in a few minutes, no telescope needed. From ${name}:city: it stays low over the southern horizon, so find a clear view to the south.`
        : $localize`:FAQ of an ISS city page:Yes. On a good pass it is brighter than any star: a steady light crossing the sky in a few minutes. No telescope or binoculars are needed.`,
    },
  ];
}

/** schema.org FAQPage, from the same questions and answers the page shows. */
export function faqJsonLd(faq: readonly Faq[]): object {
  return {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: faq.map((item) => ({
      '@type': 'Question',
      name: item.question,
      acceptedAnswer: { '@type': 'Answer', text: item.answer },
    })),
  };
}
