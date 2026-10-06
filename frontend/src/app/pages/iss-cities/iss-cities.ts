import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Reveal } from '../../motion/reveal';
import { ISS_CITIES, cityName, latitudeLabel } from '../iss-city/iss-cities';

/** The index of the ISS city pages (ABD-34): a crawler's way in, and a reader's. */
@Component({
  selector: 'app-iss-cities',
  imports: [RouterLink, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  template: `
    <section class="page-hero wrap" appReveal aria-labelledby="cities-title">
      <span class="kicker"><a routerLink="/satellites/25544" i18n>The ISS</a></span>
      <h1 id="cities-title" i18n>The ISS over your city</h1>
      <p class="lede" i18n>
        Fifty cities, each with its next passes of the International Space Station, computed as the page opens.
        Not in the list? <a routerLink="/">The predictor</a> computes them over your own position.
      </p>
    </section>
    <section class="page-section wrap" appReveal aria-label="Cities" i18n-aria-label>
      <ul class="cities">
        @for (city of cities(); track city.slug) {
          <li>
            <a [routerLink]="['/iss', city.slug]">
              <span class="name">{{ city.name }}</span>
              <span class="num latitude">{{ city.latitude }}</span>
            </a>
          </li>
        }
      </ul>
    </section>
  `,
  styles: `
    .cities { display: grid; gap: 10px; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); list-style: none; margin: 0; padding: 0; }
    .cities a {
      align-items: baseline;
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: var(--r-field);
      color: var(--ink);
      display: flex;
      justify-content: space-between;
      padding: 14px 16px;
      text-decoration: none;
      transition: border-color var(--motion-fast);
    }
    .cities a:hover, .cities a:focus-visible { border-color: var(--accent); }
    .latitude { color: var(--ink-3); font-size: 12px; }
  `,
})
export class IssCitiesPage {
  protected readonly cities = computed(() =>
    ISS_CITIES.map((city) => ({ slug: city.slug, name: cityName(city), latitude: latitudeLabel(city.lat) }))
      .sort((a, b) => a.name.localeCompare(b.name)));
}
