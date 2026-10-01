import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Reveal } from '../../motion/reveal';

/** Any address the router does not know: said plainly, with the ways back. */
@Component({
  selector: 'app-not-found',
  imports: [RouterLink, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  template: `
    <section class="page-hero wrap" appReveal aria-labelledby="nf-title">
      <span class="kicker num" i18n>404 · below the horizon</span>
      <h2 id="nf-title" i18n>This page never rose</h2>
      <p class="lede" i18n>The address does not match any page of this site. It may have been mistyped, or it moved.</p>
      <div class="actions">
        <a class="btn primary" routerLink="/" i18n>Open the predictor</a>
        <a class="btn outline" routerLink="/satellites" i18n>Browse satellites</a>
      </div>
    </section>
  `,
})
export class NotFoundPage {}
