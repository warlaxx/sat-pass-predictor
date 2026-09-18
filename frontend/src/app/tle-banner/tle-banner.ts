import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TleDto } from '../api/passes.model';
import { aosUncertaintySeconds, expectedDriftKm, formatAge } from '../format';

/**
 * The uncertainty banner: which elements served this prediction, and how much they are
 * worth by now.
 *
 * This is the part of the interface that separates the project from a tracker. Every
 * such site draws a curve; almost none says that the curve rests on a set of elements
 * published some days ago, nor what those days cost. The figures are orders of
 * magnitude, and are written as ranges so that nobody reads them as a guarantee.
 */
@Component({
  selector: 'app-tle-banner',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let t = tle();
    <section class="banner" [class.stale]="isStale()">
      <div class="row">
        <span class="label">Orbital elements</span>
        <span class="num age">{{ age() }} old</span>
        <span class="source">{{ t.source }}</span>
      </div>

      <p class="detail">
        Epoch <span class="num">{{ t.epoch | date: 'yyyy-MM-dd HH:mm' : 'UTC' }} UTC</span>,
        fetched <span class="num">{{ t.fetchedAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }} UTC</span>.
        Indicative SGP4 position drift:
        <span class="num">{{ drift().low }} to {{ drift().high }} km</span> since the epoch.
        Approximate timing scale:
        <span class="num">{{ uncertainty() === 0 ? 'less than 1 s' : 'about ±' + uncertainty() + ' s' }}</span>.
        These are rough estimates, not accuracy guarantees.
      </p>
    </section>
  `,
  styles: `
    .banner {
      background: color-mix(in srgb, var(--warn) 8%, var(--panel));
      border: 1px solid color-mix(in srgb, var(--warn) 35%, var(--line));
      border-radius: var(--r);
      padding: 0.85rem 1.1rem;
    }

    .banner.stale {
      background: color-mix(in srgb, var(--hot) 10%, var(--panel));
      border-color: color-mix(in srgb, var(--hot) 45%, var(--line));
    }

    .row {
      align-items: baseline;
      display: flex;
      flex-wrap: wrap;
      gap: 0.6rem;
    }

    .age {
      color: var(--warn);
      font-size: 1.05rem;
    }

    .stale .age {
      color: var(--hot);
    }

    .source {
      color: var(--ink-3);
      font-size: 0.8rem;
      margin-left: auto;
    }

    .detail {
      color: var(--ink-2);
      font-size: 0.86rem;
      margin: 0.4rem 0 0;
    }

    .detail .num {
      color: var(--ink);
    }
  `,
})
export class TleBanner {
  readonly tle = input.required<TleDto>();

  readonly age = computed(() => formatAge(this.tle().ageSeconds));
  readonly drift = computed(() => expectedDriftKm(this.tle().ageSeconds));
  readonly uncertainty = computed(() => aosUncertaintySeconds(this.tle().ageSeconds));

  /**
   * Past three days the elements are old enough for the banner to change colour.
   *
   * The backend refuses outright past seven days (`tle.max-age`); this is the warning
   * before the refusal, not a second rule. The two numbers are deliberately different:
   * one says "read this with care", the other says "this would be made up".
   */
  readonly isStale = computed(() => this.tle().ageSeconds > 3 * 86_400);
}
