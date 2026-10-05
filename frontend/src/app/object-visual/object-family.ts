import { SpaceObject } from '../api/separations.model';

/**
 * What an object looks like, broadly, for the illustration drawn when it has no free
 * photograph (ABD-46): about 93 % of the catalogue, since stages, adapters, fairings and
 * debris are almost never photographed.
 *
 * Read from the record's role (the first letter of its GCAT type) and, for components and
 * payloads, from the name and the mass: GCAT names launch hardware plainly ("Fairing",
 * "Payload Adapter", "Apogee Kick Motor"), and a payload of a few kilograms is a cubesat.
 */
export type ObjectFamily = 'satellite' | 'cubesat' | 'stage' | 'fairing' | 'motor' | 'hardware' | 'debris' | 'unknown';

/** The heaviest 12U cubesats weigh about 24 kg; a 30 kg payload is no longer one. */
export const CUBESAT_MAX_KG = 25;

const FAIRING = /\b(fairing|shroud|nose ?cone)\b/i;
const MOTOR = /\b(motor|engine|ullage|SOZ|kick|PAM|Star[- ]?\d+\w*|booster)\b/i;
const CUBESAT = /\b(cube ?sat|\d{1,2}U)\b/i;

export function objectFamily(object: Pick<SpaceObject, 'role' | 'name' | 'massKg'>): ObjectFamily {
  const name = object.name ?? '';
  switch (object.role) {
    case 'debris':
      return 'debris';
    case 'rocket-stage':
      return 'stage';
    case 'component':
      if (FAIRING.test(name)) return 'fairing';
      if (MOTOR.test(name)) return 'motor';
      // Adapters, dispensers, carriers, covers, rings: launch hardware that holds things.
      return 'hardware';
    case 'payload':
      if (CUBESAT.test(name)) return 'cubesat';
      if (object.massKg !== null && object.massKg > 0 && object.massKg <= CUBESAT_MAX_KG) return 'cubesat';
      return 'satellite';
    default:
      return 'unknown';
  }
}

/** The family's name, as the illustration's caption and alternative text. */
export function familyLabel(family: ObjectFamily): string {
  switch (family) {
    case 'satellite': return $localize`:Kind of object, under its illustration:Satellite`;
    case 'cubesat': return $localize`:Kind of object, under its illustration:Small satellite (cubesat)`;
    case 'stage': return $localize`:Kind of object, under its illustration:Rocket stage`;
    case 'fairing': return $localize`:Kind of object, under its illustration:Payload fairing`;
    case 'motor': return $localize`:Kind of object, under its illustration:Rocket motor`;
    case 'hardware': return $localize`:Kind of object, under its illustration:Launch hardware (adapter, dispenser)`;
    case 'debris': return $localize`:Kind of object, under its illustration:Debris`;
    case 'unknown': return $localize`:Kind of object, under its illustration:Space object`;
  }
}
