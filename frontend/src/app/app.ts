import {
  ChangeDetectionStrategy, Component, DOCUMENT, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, viewChild,
} from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { ACCOUNT_URL, OPERATOR, REPOSITORY_URL } from './shared/site';
import { LANGUAGES, addressIn, currentLanguage, pathIn } from './shared/locale';
import { feedbackHref } from './shared/feedback';
import { SITE_ORIGIN } from './shared/seo';
import { ServiceStatus } from './shared/service-status';
import { RouteCurtain } from './motion/route-curtain';
import { ApiWaitNotice } from './shared/api-wait-notice';
import { NAV_PAGES } from './motion/route-transition';
import { SiteFooter } from './shell/site-footer';

const STATUS_LABELS = {
  checking: $localize`Checking status…`,
  ok: $localize`All systems nominal`,
  slow: $localize`Answering, slowly`,
  down: $localize`Service unavailable`,
} as const;

/**
 * The shell: the header, the footer, and whichever page the address names.
 *
 * It holds no prediction state. The predictor is one page among others now, and a shell
 * that knew about passes would re-render the header every time a search came back.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, RouteCurtain, ApiWaitNotice, SiteFooter],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly accountUrl = ACCOUNT_URL;

  protected readonly links = NAV_PAGES.map((page, index) => ({
    ...page, exact: page.path === '/', number: String(index + 1).padStart(2, '0'),
  }));

  private readonly router = inject(Router);
  protected readonly url = signal(this.router.url);
  private readonly language = currentLanguage();
  /** The same page in the other language: a full load of the other build. */
  protected readonly otherLanguage = computed(() => {
    const other = LANGUAGES[this.language === 'fr' ? 'en' : 'fr'];
    return { ...other, href: addressIn(other.code, this.url()) };
  });

  /** True once an operator address is published: the notice then asks for feedback by e-mail. */
  protected readonly feedbackByMail = Boolean(OPERATOR.email);
  protected readonly feedbackHref = computed(() =>
    feedbackHref(OPERATOR.email, REPOSITORY_URL, SITE_ORIGIN + pathIn(this.language, this.url())));

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
    const document = inject(DOCUMENT);
    // The page behind a full-screen sheet must not scroll under the reader's thumb.
    effect(() => document.documentElement.classList.toggle('menu-locked', this.menuOpen()));
    afterNextRender(() => {
      // Widening past the breakpoint hides the sheet; it must not leave the page locked.
      const wide = window.matchMedia?.('(width >= 1180px)');
      const onChange = (event: MediaQueryListEvent): void => { if (event.matches) this.menuOpen.set(false); };
      wide?.addEventListener('change', onChange);
      destroyRef.onDestroy(() => wide?.removeEventListener('change', onChange));
    });

    const subscription = this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe((event) => {
        this.menuOpen.set(false);
        this.url.set(event.urlAfterRedirects);
      });
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
