import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Reveal } from '../../motion/reveal';
import { ACCOUNT_URL, DEMO_LIMITS, OPERATOR, PLANS_LINK, REPOSITORY_URL } from '../../shared/site';

/** A published fact about the operator, or the marker that it is still missing. */
interface IdentityLine {
  readonly label: string;
  readonly value: string;
}

/**
 * Legal notice, terms of use and privacy policy, on one page with three anchors.
 *
 * The data inventory is taken from the code, not from a template: what the migrations
 * store (a GitHub numeric id, a key hash, daily counters, a Stripe customer id), what the
 * predictor sends (the coordinates of a query) and what it does not (no cookie, no
 * third-party font) - the AdSense script in index.html being the one third party that
 * may set cookies, after the consent message Google shows in Europe. What the code cannot know - who the operator is, how long logs are
 * kept - is left visibly blank. Milestone 15 is the review that fills it.
 *
 * The publisher is a private individual, not a company: the notice names a person, a
 * contact and the hosts (LCEN art. 6), and asks for no legal form, SIREN or VAT number.
 */
@Component({
  selector: 'app-legal',
  imports: [DatePipe, RouterLink, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './legal.html',
  styles: `
    .version { color: var(--ink-3); }
    .toc { display: flex; flex-wrap: wrap; gap: 12px; }
    dl { display: grid; gap: 10px 32px; grid-template-columns: max-content 1fr; margin: 0; }
    dt { color: var(--ink-3); }
    dd { color: var(--ink); margin: 0; }
    @media (width < 640px) { dl { grid-template-columns: 1fr; } dd { margin-bottom: 8px; } }
  `,
})
export class LegalPage {
  protected readonly accountUrl = ACCOUNT_URL;
  protected readonly plans = PLANS_LINK;
  protected readonly repositoryUrl = REPOSITORY_URL;
  protected readonly demo = DEMO_LIMITS;
  protected readonly operatorEmail = OPERATOR.email;

  /** The date of the version in force, quoted by the terms; move it with every change of substance. */
  protected readonly version = '2026-10-03';

  protected readonly identity: readonly IdentityLine[] = [
    { label: $localize`Publisher`, value: OPERATOR.name },
    { label: $localize`Address`, value: OPERATOR.address },
    { label: $localize`Contact`, value: OPERATOR.email },
    { label: $localize`Publication director`, value: OPERATOR.publicationDirector },
  ];

  /** True while any mandatory fact is blank: the page then says it is a draft. */
  protected readonly incomplete = Object.values(OPERATOR).some((value) => value === '');
}
