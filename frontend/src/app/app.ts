import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { ACCOUNT_URL, REPOSITORY_URL, SWAGGER_URL } from './shared/site';

/**
 * The shell: the header, the footer, and whichever page the address names.
 *
 * It holds no prediction state. The predictor is one page among others now, and a shell
 * that knew about passes would re-render the header every time a search came back.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly accountUrl = ACCOUNT_URL;
  protected readonly repositoryUrl = REPOSITORY_URL;
  protected readonly swaggerUrl = SWAGGER_URL;

  protected readonly links = [
    { path: '/', label: 'Predictor', exact: true },
    { path: '/satellites', label: 'Satellites', exact: false },
    { path: '/alerts', label: 'Alerts', exact: false },
    { path: '/developers', label: 'Developers', exact: false },
    { path: '/pricing', label: 'Pricing', exact: false },
    { path: '/methodology', label: 'Methodology', exact: false },
    { path: '/status', label: 'Status', exact: false },
  ] as const;

  /** The narrow-screen menu. Closed by every navigation, so a tap on a link is enough. */
  protected readonly menuOpen = signal(false);

  constructor() {
    const subscription = inject(Router).events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => this.menuOpen.set(false));
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }
}
