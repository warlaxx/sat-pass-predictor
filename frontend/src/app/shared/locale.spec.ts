import { describe, it, expect } from 'vitest';
import { addressIn, languageOf, pathIn } from './locale';

describe('locale', () => {
  it('reads the language of a build from its locale id', () => {
    expect(languageOf('fr')).toBe('fr');
    expect(languageOf('fr-FR')).toBe('fr');
    expect(languageOf('en-US')).toBe('en');
  });

  it('places a router path in each language, English at the root', () => {
    expect(pathIn('en', '/')).toBe('/');
    expect(pathIn('fr', '/')).toBe('/fr/');
    expect(pathIn('fr', '/satellites/25544')).toBe('/fr/satellites/25544');
    expect(pathIn('en', '/pricing?x=1#faq')).toBe('/pricing');
  });

  it('keeps the search and the fragment when switching language', () => {
    expect(addressIn('fr', '/?norad=25544&lat=45#table')).toBe('/fr/?norad=25544&lat=45#table');
    expect(addressIn('en', '/legal#privacy')).toBe('/legal#privacy');
  });
});
