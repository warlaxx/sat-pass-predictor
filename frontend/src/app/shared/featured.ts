/**
 * The featured satellites: the cards of the satellites page, and the satellite pages
 * that are prerendered at build time (see `app.routes.server.ts`).
 */
export interface FeaturedSatellite {
  readonly noradId: number;
  readonly name: string;
  readonly blurb: string;
}

export interface FeaturedGroup {
  readonly title: string;
  readonly satellites: readonly FeaturedSatellite[];
}

/**
 * A few satellites worth looking up, chosen for what a visitor can do with them - see
 * them, photograph them, hear them - and a search over everything else.
 *
 * The names here are labels for the cards only. The satellite page shows the name the
 * catalogue publishes, and an object that re-entered since this list was written gets
 * the API's "unknown satellite" answer rather than invented passes.
 */
export const FEATURED: readonly FeaturedGroup[] = [
  {
    title: $localize`:Group of satellites:Crewed stations`,
    satellites: [
      { noradId: 25544, name: $localize`:Satellite name:International Space Station`, blurb: $localize`The brightest satellite in the sky, often brighter than any star. Crewed since 2000.` },
      { noradId: 48274, name: $localize`:Satellite name:Tiangong (Tianhe core module)`, blurb: $localize`China's space station, in a lower-inclination orbit: easy to see from southern Europe.` },
    ],
  },
  {
    title: $localize`:Group of satellites:Bright to the eye`,
    satellites: [
      { noradId: 53807, name: $localize`:Satellite name:BlueWalker 3`, blurb: $localize`A test satellite whose 64 m² antenna array makes it one of the brightest objects at night.` },
    ],
  },
  {
    title: $localize`:Group of satellites:Science`,
    satellites: [
      { noradId: 20580, name: $localize`:Satellite name:Hubble Space Telescope`, blurb: $localize`At 28.5° inclination, visible only from latitudes below about 50°.` },
    ],
  },
  {
    title: $localize`:Group of satellites:Amateur radio`,
    satellites: [
      { noradId: 27607, name: $localize`:Satellite name:SaudiSat-1C (SO-50)`, blurb: $localize`A long-lived FM repeater, a classic first contact through a satellite.` },
      { noradId: 44909, name: $localize`:Satellite name:RS-44`, blurb: $localize`A linear transponder on its Russian upper stage, higher than most: long passes, long contacts.` },
      { noradId: 7530, name: $localize`:Satellite name:AMSAT-OSCAR 7 (AO-7)`, blurb: $localize`Launched in 1974, still relaying, but only in sunlight: its batteries failed long ago.` },
      { noradId: 39444, name: $localize`:Satellite name:FUNcube-1 (AO-73)`, blurb: $localize`Educational CubeSat whose telemetry schools decode, with a linear transponder.` },
    ],
  },
  {
    title: $localize`:Group of satellites:Weather`,
    satellites: [
      { noradId: 57166, name: $localize`:Satellite name:Meteor-M N2-3`, blurb: $localize`Russian weather satellite: its LRPT images are received at 137 MHz with an SDR.` },
      { noradId: 59051, name: $localize`:Satellite name:Meteor-M N2-4`, blurb: $localize`The newest Meteor-M, launched in 2024, sending the same LRPT images.` },
      { noradId: 43013, name: $localize`:Satellite name:NOAA 20 (JPSS-1)`, blurb: $localize`US polar weather satellite whose VIIRS images the whole Earth twice a day.` },
    ],
  },
  {
    title: $localize`:Group of satellites:Earth observation`,
    satellites: [
      { noradId: 25994, name: $localize`:Satellite name:Terra`, blurb: $localize`NASA flagship with MODIS and ASTER, crossing the equator mid-morning.` },
      { noradId: 27424, name: $localize`:Satellite name:Aqua`, blurb: $localize`Terra’s afternoon twin, measuring the water cycle.` },
      { noradId: 39084, name: $localize`:Satellite name:Landsat 8`, blurb: $localize`Half of the longest-running Earth imaging record, sun-synchronous at 705 km.` },
      { noradId: 49260, name: $localize`:Satellite name:Landsat 9`, blurb: $localize`The other half: together, a new image of every place every eight days.` },
      { noradId: 40697, name: $localize`:Satellite name:Sentinel-2A`, blurb: $localize`Copernicus multispectral imager, 10 m resolution over land.` },
    ],
  },
];

/** The featured entry for a catalogue number, if there is one. */
export function featuredSatellite(noradId: number | undefined): FeaturedSatellite | undefined {
  if (noradId === undefined) return undefined;
  return FEATURED.flatMap(group => group.satellites).find(satellite => satellite.noradId === noradId);
}
