import { ChangeDetectionStrategy, Component, DestroyRef, afterNextRender, computed, inject, input, output, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PassDto, PassesResponse } from '../api/passes.model';
import { DEFAULT_QUERY, PassQuery } from '../api/passes.query';
import { compassPoint, utcOffsetLabel } from '../format';
import { visibilityRadiusDeg } from '../globe/globe-geometry';
import { nextPassState } from '../next-pass/next-pass-state';
import { orbitFromTle } from '../shared/orbit';
import { HeroGlobe } from './hero-globe';

const MU_KM3_S2 = 398_600.4418;
const EARTH_RADIUS_KM = 6378.137;
/** The satellite and the place of the defaults, named: the form only knows their numbers. */
const DEFAULT_NAME = 'ISS (ZARYA)';
const DEFAULT_PLACE = 'Lyon';

/** "T−02:14:37": hours, minutes and seconds to go, never negative. */
export function tMinus(milliseconds: number): string {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000));
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `T−${pad(Math.floor(seconds / 3600))}:${pad(Math.floor(seconds / 60) % 60)}:${pad(seconds % 60)}`;
}

/** "6 m 48 s". */
export function shortDuration(seconds: number): string {
  const whole = Math.max(0, Math.round(seconds));
  return `${Math.floor(whole / 60)} m ${String(whole % 60).padStart(2, '0')} s`;
}

type Tone = 'go' | 'quiet' | 'busy';

function placeName({ lat, lon }: { lat: number; lon: number }): string {
  if (lat === DEFAULT_QUERY.lat && lon === DEFAULT_QUERY.lon) return DEFAULT_PLACE;
  const ns = lat < 0 ? $localize`:Compass, short:S` : $localize`:Compass, short:N`;
  const ew = lon < 0 ? $localize`:Compass, short:W` : $localize`:Compass, short:E`;
  return `${Math.abs(lat).toFixed(2)}° ${ns}, ${Math.abs(lon).toFixed(2)}° ${ew}`;
}

/**
 * The first screen of the predictor: what it does, the way in, and a countdown to the next
 * pass over a globe that turns behind it.
 *
 * Every figure on it is either computed or a dash. The page hands it the next passes of
 * the ISS over Lyon as it opens, from an unmetered endpoint, so the countdown runs before
 * anyone has filled the form (ABD-31); until they arrive - and in the prerendered HTML a
 * crawler reads - the card says in words what it is waiting for rather than showing dashes
 * alone. The globe follows the form as it is typed or located, so the observer moves when
 * the coordinates do, even with an earlier result still on screen.
 */
@Component({
  selector: 'app-hero',
  imports: [DatePipe, DecimalPipe, RouterLink, HeroGlobe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './hero.html',
  styleUrl: './hero.scss',
})
export class Hero {
  readonly query = input.required<PassQuery>();
  readonly response = input<PassesResponse>();
  readonly loading = input(false);
  /** True while the page's automatic computation is still to come or under way. */
  readonly pending = input(false);
  readonly locating = input(false);
  /** Why the position asked for from this card could not be had. */
  readonly locationError = input<string>();
  /** The call to action asks the page to compute: the form it would point to holds the query. */
  readonly compute = output<void>();
  /** "Use my position", from the card: the page locates, then computes for that position. */
  readonly locate = output<void>();

  private readonly now = signal(Date.now());

  protected readonly state = computed(() => {
    const response = this.response();
    return response ? nextPassState(response.passes, this.now()) : undefined;
  });

  /** The pass the facts describe: the one under way or next, or the last one once all have set. */
  protected readonly pass = computed<PassDto | undefined>(() => {
    const state = this.state();
    if (!state) return undefined;
    return state.kind === 'over' ? state.last : state.pass;
  });

  protected readonly satellite = computed(() => {
    const response = this.response();
    if (response) return { name: response.satellite.name, noradId: response.satellite.noradId };
    const noradId = this.query().noradId;
    return { name: noradId === DEFAULT_QUERY.noradId ? DEFAULT_NAME : `NORAD ${noradId}`, noradId };
  });

  /** Where the observer is: the response's once there is one, the form's until then. */
  protected readonly observer = computed(() => {
    const observer = this.response()?.observer;
    const query = this.query();
    return observer
      ? { lat: observer.latitudeDeg, lon: observer.longitudeDeg, alt: observer.altitudeM }
      : { lat: query.lat, lon: query.lon, alt: query.alt };
  });

  /**
   * Where the observer stands now: the form's position, which the browser's geolocation
   * fills. The globe's dot and the observer readout follow it, so a located user sees the
   * dot on their exact position at once, before or without a new computation - the
   * response's observer would keep it on the previous search's place.
   */
  protected readonly position = computed(() => {
    const { lat, lon, alt } = this.query();
    return { lat, lon, alt };
  });

  /** The place the computed pass is for, in the heading. */
  protected readonly place = computed(() => placeName(this.observer()));
  /** The place the dot stands on, in the observer readout. */
  protected readonly positionPlace = computed(() => placeName(this.position()));

  protected readonly orbit = computed(() => {
    const line2 = this.response()?.tle.line2;
    const orbit = line2 ? orbitFromTle(line2) : undefined;
    if (!orbit) return undefined;
    const altitudeKm = (orbit.perigeeKm + orbit.apogeeKm) / 2;
    return {
      inclinationDeg: orbit.inclinationDeg,
      altitudeKm,
      speedKmS: Math.sqrt(MU_KM3_S2 / (EARTH_RADIUS_KM + altitudeKm)),
    };
  });

  /** The ground circle from which the satellite clears the threshold, at the ISS's height until known. */
  protected readonly visibility = computed(() =>
    visibilityRadiusDeg(this.orbit()?.altitudeKm ?? 420, this.response()?.minElevationDeg ?? this.query().minElevation));

  protected readonly heading = computed(() => {
    const name = this.satellite().name;
    const place = this.place();
    return this.state()?.kind === 'now'
      ? $localize`Passing now · ${name}:satellite: over ${place}:place:`
      : $localize`Next pass · ${name}:satellite: over ${place}:place:`;
  });

  protected readonly countdown = computed(() => {
    const state = this.state();
    if (!state) return 'T−––:––:––';
    if (state.kind === 'now') return tMinus(state.remainingMs);
    if (state.kind === 'next') return tMinus(state.waitMs);
    return tMinus(0);
  });

  protected readonly tag = computed<{ text: string; tone: Tone }>(() => {
    const response = this.response();
    if (this.loading() || (!response && this.pending())) return { text: $localize`COMPUTING`, tone: 'busy' };
    if (!response) return { text: $localize`NOT YET COMPUTED`, tone: 'quiet' };
    const state = this.state();
    if (!state) return { text: $localize`NO PASS`, tone: 'quiet' };
    if (state.kind === 'now') return { text: $localize`ABOVE HORIZON`, tone: 'go' };
    if (state.kind === 'over') return { text: $localize`WINDOW OVER`, tone: 'quiet' };
    return state.visibleFrom ? { text: $localize`VISIBLE`, tone: 'go' } : { text: $localize`NOT VISIBLE`, tone: 'quiet' };
  });

  /** One line under the facts, so that the card reads as a sentence whatever its state. */
  protected readonly note = computed(() => {
    const pass = this.pass();
    if (pass) return $localize`Times are local (${utcOffsetLabel(pass.aos.instant)}:zone:).`;
    if (this.response()) return $localize`No pass above the threshold in this window.`;
    if (this.loading() || this.pending()) return $localize`Computed live as the page opens, from the latest orbital elements.`;
    return $localize`Not computed yet: press Compute passes, or use your position.`;
  });

  protected readonly legend = computed(() => [
    { name: this.response() ? this.satellite().name : 'ISS', colour: 'var(--signal)' },
    { name: 'NOAA 19', colour: '#fff' },
    { name: 'METEOR-M2', colour: 'var(--lit)' },
    { name: 'HST', colour: 'var(--swath)' },
  ]);

  protected readonly compass = compassPoint;
  protected readonly duration = shortDuration;
  protected readonly abs = Math.abs;
  protected readonly north = $localize`:Compass, short:N`;
  protected readonly south = $localize`:Compass, short:S`;
  protected readonly east = $localize`:Compass, short:E`;
  protected readonly west = $localize`:Compass, short:W`;

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const timer = setInterval(() => this.now.set(Date.now()), 1000);
      destroyRef.onDestroy(() => clearInterval(timer));
    });
  }
}
