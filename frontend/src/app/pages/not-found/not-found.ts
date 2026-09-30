import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Any address the router does not know: said plainly, with the ways back. */
@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  template: `
    <section class="page-hero wrap" aria-labelledby="nf-title">
      <span class="kicker num">404 · below the horizon</span>
      <h2 id="nf-title">This page never rose</h2>
      <p class="lede">The address does not match any page of this site. It may have been mistyped, or it moved.</p>
      <div class="actions">
        <a class="btn primary" routerLink="/">Open the predictor</a>
        <a class="btn ghost" routerLink="/satellites">Browse satellites</a>
      </div>
    </section>
  `,
})
export class NotFoundPage {}
