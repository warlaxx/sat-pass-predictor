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
    <section class="banner" [class.stale]="isStale()" aria-label="Freshness of the orbital elements">
      <div class="inner">
        <svg class="icon" width="22" height="22" viewBox="0 0 22 22" aria-hidden="true">
          <circle cx="11" cy="11" r="9.5" fill="none" stroke="currentColor" stroke-width="1.6" />
          <path d="M11 5.5 V11 L14.5 13" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>

        <div class="fact">
          <span class="label">TLE epoch</span>
          <span class="num value">{{ t.epoch | date: 'yyyy-MM-dd HH:mm' : 'UTC' }} UTC</span>
        </div>
        <div class="fact">
          <span class="label">Age</span>
          <span class="num value age">{{ age() }}</span>
        </div>
        <div class="fact">
          <span class="label">Fetched from {{ t.source }}</span>
          <span class="num value">{{ t.fetchedAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }} UTC</span>
        </div>

        <p class="detail">
          Indicative SGP4 drift at this age:
          <b class="num">{{ drift().low }} to {{ drift().high }} km</b>, a timing scale of
          <b class="num">{{ uncertainty() === 0 ? 'less than 1 s' : 'about ±' + uncertainty() + ' s' }}</b>
          on rise times. Times are shown to the second but are worth no better than this;
          these are rough estimates, not accuracy guarantees.
        </p>
      </div>
    </section>
  `,
  styles: `
    .banner {
      --tone: var(--warn);
      background: #120d05;
      border-bottom: 1px solid #3b2a10;
      padding-block: 22px;
      padding-inline: max(var(--gutter), (100% - 1280px) / 2);
    }

    .banner.stale {
      --tone: var(--hot);
      background: #170905;
      border-bottom-color: #4a1d12;
    }

    .inner {
      align-items: center;
      display: flex;
      flex-wrap: wrap;
      gap: 16px 40px;
    }

    .icon {
      color: var(--tone);
      flex-shrink: 0;
    }

    .fact {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .value {
      color: var(--ink);
      font-size: 16px;
    }

    .value.age {
      color: var(--tone);
      font-size: 22px;
      line-height: 1.15;
    }

    .detail {
      color: var(--ink-2);
      flex: 1 1 30ch;
      font-size: 14px;
      line-height: 1.55;
      margin: 0;
      max-width: 62ch;
    }

    .detail b {
      color: var(--tone);
      font-weight: 500;
    }

    @media (width < 640px) {
      .banner {
        padding-block: 18px;
      }

      .inner {
        gap: 14px 24px;
      }

      .icon {
        align-self: flex-start;
      }

      .detail {
        flex-basis: 100%;
      }
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
