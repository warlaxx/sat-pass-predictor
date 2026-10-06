import { describe, it, expect } from 'vitest';
import { ObjectFamily, objectFamily } from './object-family';
import { ObjectRole } from '../api/separations.model';

const of = (role: ObjectRole, name: string | null, massKg: number | null = null): ObjectFamily =>
  objectFamily({ role, name, massKg });

describe('objectFamily', () => {
  /** Names as GCAT writes them, from rows of the production import. */
  it('reads launch hardware from the component name', () => {
    expect(of('component', 'Fairing')).toBe('fairing');
    expect(of('component', 'Payload Fairing Half')).toBe('fairing');
    expect(of('component', 'Apogee Kick Motor')).toBe('motor');
    expect(of('component', 'SOZ Ullage Motor')).toBe('motor');
    expect(of('component', 'Star 48B')).toBe('motor');
    expect(of('component', 'Payload Adapter')).toBe('hardware');
    expect(of('component', 'Dispenser')).toBe('hardware');
    expect(of('component', null)).toBe('hardware');
  });

  it('tells a cubesat from a satellite by name or mass', () => {
    expect(of('payload', 'Flock 4q-12', 5)).toBe('cubesat');
    expect(of('payload', 'Lemur-2 6U demo', null)).toBe('cubesat');
    expect(of('payload', 'CubeSat XI-IV', null)).toBe('cubesat');
    expect(of('payload', 'Starlink-11202', 800)).toBe('satellite');
    expect(of('payload', 'USA 667', null)).toBe('satellite');
    // A missing or zero mass says nothing.
    expect(of('payload', 'Torga', 0)).toBe('satellite');
  });

  it('maps stages, debris and the rest', () => {
    expect(of('rocket-stage', 'Centaur AV-101')).toBe('stage');
    expect(of('debris', 'Meteor 2-5 Deb')).toBe('debris');
    expect(of('other', 'Unknown')).toBe('unknown');
    // A name that merely contains "Star" is not a motor.
    expect(of('component', 'Starlink dispenser')).toBe('hardware');
  });
});
