import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Reveal } from '../../motion/reveal';
import { SatellitePicker } from '../../satellite-picker/satellite-picker';
import { SatelliteMatch } from '../../api/satellites.service';

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
    title: 'Crewed stations',
    satellites: [
      { noradId: 25544, name: 'International Space Station', blurb: 'The brightest satellite in the sky, often brighter than any star. Crewed since 2000.' },
      { noradId: 48274, name: 'Tiangong (Tianhe core module)', blurb: "China's space station, in a lower-inclination orbit: easy to see from southern Europe." },
    ],
  },
  {
    title: 'Science',
    satellites: [
      { noradId: 20580, name: 'Hubble Space Telescope', blurb: 'At 28.5° inclination, visible only from latitudes below about 50°.' },
    ],
  },
  {
    title: 'Amateur radio',
    satellites: [
      { noradId: 27607, name: 'SaudiSat-1C (SO-50)', blurb: 'A long-lived FM repeater, a classic first contact through a satellite.' },
    ],
  },
  {
    title: 'Earth observation',
    satellites: [
      { noradId: 25994, name: 'Terra', blurb: 'NASA flagship with MODIS and ASTER, crossing the equator mid-morning.' },
      { noradId: 27424, name: 'Aqua', blurb: 'Terra’s afternoon twin, measuring the water cycle.' },
      { noradId: 39084, name: 'Landsat 8', blurb: 'Half of the longest-running Earth imaging record, sun-synchronous at 705 km.' },
      { noradId: 49260, name: 'Landsat 9', blurb: 'The other half: together, a new image of every place every eight days.' },
      { noradId: 40697, name: 'Sentinel-2A', blurb: 'Copernicus multispectral imager, 10 m resolution over land.' },
    ],
  },
];

@Component({
  selector: 'app-satellites',
  imports: [RouterLink, Reveal, SatellitePicker],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  template: `
    <section class="page-hero wrap" appReveal aria-labelledby="sats-title">
      <span class="kicker">Satellites</span>
      <h2 id="sats-title">Pick something to look up at</h2>
      <p class="lede">
        A few satellites worth following, and every active one CelesTrak publishes. Each
        page shows the orbit, how fresh its elements are, and its next passes.
      </p>
      <div class="search">
        <span class="caption" id="sats-search">Search the catalogue by name or NORAD number</span>
        <div class="field">
          <app-satellite-picker mode="add" label="Search the catalogue" describedBy="sats-search"
                                placeholder="Starlink, NOAA, 43013…" (picked)="open($event)" />
        </div>
      </div>
    </section>

    @for (group of groups; track group.title) {
      <section class="page-section wrap" appReveal [attr.aria-label]="group.title">
        <div class="section-head"><h2>{{ group.title }}</h2></div>
        <div class="cards">
          @for (satellite of group.satellites; track satellite.noradId) {
            <a class="card" [routerLink]="['/satellites', satellite.noradId]">
              <span class="num muted">NORAD {{ satellite.noradId }}</span>
              <h3>{{ satellite.name }}</h3>
              <p>{{ satellite.blurb }}</p>
            </a>
          }
        </div>
      </section>
    }
  `,
  styles: `
    .search { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; max-width: 420px; }
    .search app-satellite-picker { width: 100%; }
  `,
})
export class SatellitesPage {
  private readonly router = inject(Router);
  protected readonly groups = FEATURED;

  protected open(match: SatelliteMatch): void {
    void this.router.navigate(['/satellites', match.noradId]);
  }
}
