/**
 * `GET /api/separations` and `GET /api/separations/{id}`: objects released by other
 * objects in orbit (see SeparationController). Field for field what the backend writes.
 */

export type SeparationKind = 'RELEASE' | 'FRAGMENTATION';

export type DatePrecision = 'DECADE' | 'YEAR' | 'QUARTER' | 'MONTH' | 'DAY' | 'MINUTE' | 'SECOND';

/**
 * A date with the precision it was recorded with. `at` is the start of the interval
 * `text` denotes, to sort by: never shown without `precision` (`2026 Sep?` is not the
 * first of September).
 */
export interface RecordedDate {
  readonly text: string;
  readonly at: string | null;
  readonly precision: DatePrecision | null;
  readonly uncertain: boolean;
}

/** `apogeeKm` is null for an object that left Earth orbit. */
export interface RecordedOrbit {
  readonly perigeeKm: number | null;
  readonly apogeeKm: number | null;
  readonly inclinationDeg: number | null;
  readonly orbitClass: string | null;
}

export interface Evidence {
  readonly id: string;
  readonly satcat: number | null;
  readonly piece: string | null;
  readonly name: string | null;
  readonly payloadName: string | null;
  readonly parent: string | null;
  readonly separationDate: string | null;
  readonly owner: string | null;
  readonly status: string | null;
}

export type ObjectRole = 'payload' | 'rocket-stage' | 'component' | 'debris' | 'other';

export interface SpaceObject {
  readonly id: string;
  readonly noradId: number | null;
  readonly name: string | null;
  readonly payloadName: string | null;
  readonly piece: string | null;
  readonly role: ObjectRole;
  readonly owner: string | null;
  readonly state: string | null;
  readonly massKg: number | null;
  readonly launch: RecordedDate | null;
  readonly orbit: RecordedOrbit;
  readonly inOrbit: boolean;
  readonly evidence: Evidence;
}

export interface SeparationSummary {
  readonly id: string;
  readonly kind: SeparationKind;
  readonly date: RecordedDate;
  readonly parentName: string | null;
  readonly parentOwner: string | null;
  readonly parentState: string | null;
  readonly firstChildName: string | null;
  readonly firstChildNoradId: number | null;
  readonly children: number;
  readonly orbit: RecordedOrbit;
  readonly inOrbit: boolean;
}

export interface SeparationMonth {
  /** `2026-09` */
  readonly month: string;
  readonly releases: number;
  readonly fragmentations: number;
}

export interface SeparationStats {
  readonly objects: number;
  readonly objectsThisYear: number;
  readonly year: number;
  readonly byMonth: readonly SeparationMonth[];
}

export interface SeparationsResponse {
  readonly events: readonly SeparationSummary[];
  readonly stats: SeparationStats;
  readonly updatedAt: string | null;
}

export interface SeparationEvent {
  readonly id: string;
  readonly kind: SeparationKind;
  readonly date: RecordedDate;
  readonly parent: SpaceObject | null;
  readonly grandparent: SpaceObject | null;
  readonly children: readonly SpaceObject[];
  readonly childCount: number;
  readonly updatedAt: string | null;
}
