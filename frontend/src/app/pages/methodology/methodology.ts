import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Reveal } from '../../motion/reveal';
import { REPOSITORY_URL } from '../../shared/site';

/**
 * How a pass is computed and what it is worth: the README's "Physical model" and
 * "Validation", for a reader who will never open the repository.
 *
 * The numbers are the README's, measured by scripts/validate-against-skyfield.py. When
 * the reference is regenerated, this page is one of the places to update.
 */
@Component({
  selector: 'app-methodology',
  imports: [RouterLink, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './methodology.html',
  styles: `
    .steps { counter-reset: step; }
    .steps .card { counter-increment: step; }
    .steps .card::before {
      color: var(--signal);
      content: counter(step, decimal-leading-zero);
      font-family: var(--font-mono);
      font-size: 13px;
    }
    .split { display: grid; gap: 32px 64px; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); }
  `,
})
export class MethodologyPage {
  protected readonly repositoryUrl = REPOSITORY_URL;
}
