import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, viewChild,
} from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { ACCOUNT_URL, REPOSITORY_URL, SWAGGER_URL } from './shared/site';
import { ServiceStatus } from './shared/service-status';
import { RouteCurtain } from './motion/route-curtain';
import { NAV_PAGES } from './motion/route-transition';

const STATUS_LABELS = {
  checking: 'Checking status…',
  ok: 'All systems nominal',
  slow: 'Answering, slowly',
  down: 'Service unavailable',
} as const;

/**
 * The shell: the header, the footer, and whichever page the address names.
 *
 * It holds no prediction state. The predictor is one page among others now, and a shell
 * that knew about passes would re-render the header every time a search came back.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, RouteCurtain],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly accountUrl = ACCOUNT_URL;
  protected readonly repositoryUrl = REPOSITORY_URL;
  protected readonly swaggerUrl = SWAGGER_URL;

  protected readonly links = NAV_PAGES.map((page, index) => ({
    ...page, exact: page.path === '/', number: String(index + 1).padStart(2, '0'),
  }));

  private readonly serviceStatus = inject(ServiceStatus);
  protected readonly status = this.serviceStatus.state;
  protected readonly statusLabel = computed(() => STATUS_LABELS[this.status()]);

  /**
   * The narrow-screen menu: a full-screen sheet over the page. Closed by every navigation,
   * so a tap on a link is enough, and by Escape or the close button.
   */
  protected readonly menuOpen = signal(false);
  private readonly menuToggle = viewChild.required<ElementRef<HTMLButtonElement>>('menuToggle');
  private readonly menuClose = viewChild.required<ElementRef<HTMLButtonElement>>('menuClose');
  private readonly injector = inject(Injector);

  protected toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    // The sheet covers the toggle: focus moves into it, onto its close button.
    afterNextRender(() => this.menuClose().nativeElement.focus(), { injector: this.injector });
  }

  protected closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    afterNextRender(() => this.menuToggle().nativeElement.focus(), { injector: this.injector });
  }

  constructor() {
    const destroyRef = inject(DestroyRef);
    // The page behind a full-screen sheet must not scroll under the reader's thumb.
    effect(() => document.documentElement.classList.toggle('menu-locked', this.menuOpen()));
    afterNextRender(() => {
      // Widening past the breakpoint hides the sheet; it must not leave the page locked.
      const wide = window.matchMedia?.('(width >= 1180px)');
      const onChange = (event: MediaQueryListEvent): void => { if (event.matches) this.menuOpen.set(false); };
      wide?.addEventListener('change', onChange);
      destroyRef.onDestroy(() => wide?.removeEventListener('change', onChange));
    });

    const subscription = inject(Router).events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => this.menuOpen.set(false));
    // Once the first page is drawn, not before: the probe must not compete with it.
    let timer: ReturnType<typeof setTimeout> | undefined;
    afterNextRender(() => { timer = setTimeout(() => this.serviceStatus.probe(), 1200); });
    destroyRef.onDestroy(() => {
      document.documentElement.classList.remove('menu-locked');
      subscription.unsubscribe();
      clearTimeout(timer);
    });
  }
}
