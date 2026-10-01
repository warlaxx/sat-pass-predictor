import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Reveal } from '../../motion/reveal';
import { SatellitePicker } from '../../satellite-picker/satellite-picker';
import { SatelliteMatch } from '../../api/satellites.service';
import { FEATURED } from '../../shared/featured';

@Component({
  selector: 'app-satellites',
  imports: [RouterLink, Reveal, SatellitePicker],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  template: `
    <section class="page-hero wrap" appReveal aria-labelledby="sats-title">
      <span class="kicker" i18n>Satellites</span>
      <h2 id="sats-title" i18n>Pick something to look up at</h2>
      <p class="lede" i18n>
        A few satellites worth following, and every active one CelesTrak publishes. Each
        page shows the orbit, how fresh its elements are, and its next passes.
      </p>
      <div class="search">
        <span class="caption" id="sats-search" i18n>Search the catalogue by name or NORAD number</span>
        <div class="field">
          <app-satellite-picker mode="add" label="Search the catalogue" i18n-label describedBy="sats-search"
                                placeholder="Starlink, NOAA, 43013…" i18n-placeholder (picked)="open($event)" />
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
