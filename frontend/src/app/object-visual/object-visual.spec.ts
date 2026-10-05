import { describe, beforeEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ObjectVisual } from './object-visual';
import { ObjectImage, SpaceObject } from '../api/separations.model';

const IMAGE: ObjectImage = {
  url: 'https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Hubble_2009_close-up.jpg/500px-Hubble_2009_close-up.jpg',
  width: 500,
  height: 332,
  author: 'NASA & ESA',
  licence: 'CC BY-SA 4.0',
  licenceUrl: 'https://creativecommons.org/licenses/by-sa/4.0',
  sourceUrl: 'https://commons.wikimedia.org/wiki/File:Hubble_2009_close-up.jpg',
};

function object(overrides: Partial<SpaceObject> = {}): SpaceObject {
  return {
    id: 'S20580', noradId: 20580, name: 'Hubble Space Telescope', payloadName: null, piece: '1990-037B',
    role: 'payload', owner: 'NASA', state: 'US', massKg: 11110, launch: null,
    orbit: { perigeeKm: 520, apogeeKm: 530, inclinationDeg: 28.5, orbitClass: 'LEO/I' }, inOrbit: true,
    evidence: { id: 'S20580', satcat: 20580, piece: '1990-037B', name: 'HST', payloadName: null, parent: null,
      separationDate: null, owner: 'NASA', status: 'O' },
    ...overrides,
  };
}

async function render(value: SpaceObject, variant: 'card' | 'thumb' = 'card') {
  const fixture = TestBed.createComponent(ObjectVisual);
  fixture.componentRef.setInput('object', value);
  fixture.componentRef.setInput('variant', variant);
  await fixture.whenStable();
  return fixture;
}

describe('ObjectVisual', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ObjectVisual],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();
  });

  it('shows the photograph through this site, with its credit', async () => {
    const fixture = await render(object({ image: IMAGE }));
    const element: HTMLElement = fixture.nativeElement;
    const img = element.querySelector('img')!;

    expect(img.getAttribute('src')).toBe('/commons-images/wikipedia/commons/thumb/8/85/Hubble_2009_close-up.jpg/500px-Hubble_2009_close-up.jpg');
    expect(img.getAttribute('loading')).toBe('lazy');
    expect(img.getAttribute('width')).toBe('500');
    const caption = element.querySelector('figcaption')!;
    expect(caption.textContent).toContain('Photo: NASA & ESA');
    expect(caption.querySelector('a[href="https://creativecommons.org/licenses/by-sa/4.0"]')?.textContent).toBe('CC BY-SA 4.0');
    expect(caption.querySelector(`a[href="${IMAGE.sourceUrl}"]`)).not.toBeNull();
    expect(element.textContent).not.toContain('Illustration');
  });

  it('draws the kind of object, marked as an illustration, when there is no photograph', async () => {
    const fixture = await render(object({ role: 'rocket-stage', name: 'Centaur AV-101', image: undefined }));
    const element: HTMLElement = fixture.nativeElement;

    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('svg')?.getAttribute('aria-label')).toBe('Illustration: Rocket stage');
    expect(element.querySelector('.badge')?.textContent).toBe('Illustration');
    // The card's own label names the role; the drawing adds no caption under it.
    expect(element.querySelector('figcaption')).toBeNull();
  });

  it('falls back to the illustration when the photograph fails to load', async () => {
    const fixture = await render(object({ image: IMAGE }));
    fixture.nativeElement.querySelector('img').dispatchEvent(new Event('error'));
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.querySelector('svg')).not.toBeNull();
  });

  it('never shows a picture from another host', async () => {
    const fixture = await render(object({ image: { ...IMAGE, url: 'https://evil.example.org/a.jpg' } }));
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
  });

  it('keeps a credit link on a thumbnail', async () => {
    const fixture = await render(object({ image: IMAGE }), 'thumb');
    const credit: HTMLAnchorElement = fixture.nativeElement.querySelector('a.credit');

    expect(credit.getAttribute('href')).toBe(IMAGE.sourceUrl);
    expect(credit.getAttribute('title')).toBe('Photo: NASA & ESA, CC BY-SA 4.0, Wikimedia Commons');
    expect(fixture.nativeElement.querySelector('figcaption')).toBeNull();
  });
});
