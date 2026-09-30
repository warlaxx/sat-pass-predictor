import { ChangeDetectionStrategy, Component } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Reveal } from '../../motion/reveal';
import { ACCOUNT_URL, BATCH_LIMITS, DEMO_LIMITS, PLANS } from '../../shared/site';

/**
 * Plans and quotas, as docs/billing.md defines them.
 *
 * No price is printed. The €9 and €49 of the roadmap are hypotheses, billing is disabled
 * on the public deployment, and milestone 15 asks for the data-rights question to be
 * settled before a price is published. The page says "not open yet" rather than showing a
 * number nobody can pay; the quotas themselves are real and enforced today.
 */
@Component({
  selector: 'app-pricing',
  imports: [RouterLink, DecimalPipe, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './pricing.html',
  styles: `
    .plan { position: relative; }
    .plan .quota { font-size: 36px; letter-spacing: -0.02em; }
    .plan .per { color: var(--ink-3); font-size: 13px; }
    .plan ul { color: var(--ink-2); display: flex; flex-direction: column; gap: 8px; list-style: none; margin: 6px 0 0; padding: 0; }
    .plan li { display: flex; gap: 10px; }
    .plan li::before { color: var(--observer); content: '✓'; }
    .plan .badge {
      align-self: flex-start;
      border: 1px solid currentColor;
      border-radius: 99px;
      color: var(--ink-3);
      font-size: 12px;
      padding: 3px 10px;
    }
    .plan.open .badge { color: var(--observer); }
    .plan footer { margin-top: auto; padding-top: 14px; }
    .plan.featured { border-color: var(--accent-dim); }
    dl { display: grid; gap: 22px; margin: 0; max-width: 80ch; }
    dt { color: var(--ink); font-weight: 600; margin-bottom: 6px; }
    dd { color: var(--ink-2); margin: 0; }
  `,
})
export class PricingPage {
  protected readonly plans = PLANS;
  protected readonly demo = DEMO_LIMITS;
  protected readonly batch = BATCH_LIMITS;
  protected readonly accountUrl = ACCOUNT_URL;
}
