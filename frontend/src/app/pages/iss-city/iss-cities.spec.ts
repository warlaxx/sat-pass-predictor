import { describe, it, expect } from 'vitest';
import { ISS_CITIES, cityFaq, cityIntro, cityOf, highestElevationDeg, issCity, latitudeLabel, trackBand } from './iss-cities';

describe('ISS city pages', () => {
  it('names fifty cities, each once', () => {
    expect(ISS_CITIES).toHaveLength(50);
    expect(new Set(ISS_CITIES.map((city) => city.slug)).size).toBe(50);
    expect(issCity('paris')?.en).toBe('Paris');
    expect(issCity('atlantis')).toBeUndefined();
  });

  it('places a city relative to the track, which turns at 51.6°', () => {
    expect(trackBand(issCity('singapore')!.lat)).toBe('tropics');
    expect(trackBand(issCity('lyon')!.lat)).toBe('under');
    expect(trackBand(issCity('london')!.lat)).toBe('edge');
    expect(trackBand(issCity('edinburgh')!.lat)).toBe('beyond');
    expect(trackBand(issCity('sydney')!.lat)).toBe('under');
  });

  it('lets the ISS climb overhead under its track, and only so high beyond it', () => {
    expect(highestElevationDeg(45)).toBe(90);
    // Edinburgh is 4.3° beyond the edge: the best passes peak near 38°.
    expect(highestElevationDeg(55.95)).toBeCloseTo(38, 0);
    expect(highestElevationDeg(-55.95)).toBeCloseTo(38, 0);
  });

  /** ABD-34: an introduction that is not one sentence with the name swapped. */
  it('writes an introduction that differs from city to city beyond the name', () => {
    const withoutName = new Set(ISS_CITIES.map((city) => cityIntro(city).replaceAll(city.en, '·')));
    expect(withoutName.size).toBe(50);
    expect(cityIntro(issCity('edinburgh')!)).toContain('38°');
    expect(latitudeLabel(-33.8688)).toBe('33.9° S');
  });

  it('asks the two questions the page answers', () => {
    const faq = cityFaq(issCity('edinburgh')!);
    expect(faq.map((item) => item.question)).toEqual([
      'When can I see the ISS from Edinburgh?', 'Can you see the ISS with the naked eye from Edinburgh?']);
    expect(faq[1].answer).toContain('southern horizon');
  });

  it('elides "de" in French: d’Édimbourg, du Cap', () => {
    expect(cityOf(issCity('edinburgh')!, 'fr')).toBe("d'Édimbourg");
    expect(cityOf(issCity('cape-town')!, 'fr')).toBe('du Cap');
    expect(cityOf(issCity('paris')!, 'fr')).toBe('de Paris');
    expect(cityOf(issCity('paris')!, 'en')).toBe('Paris');
  });
});
