import { DOCUMENT, DestroyRef, Injectable, inject } from '@angular/core';
import {
  CanActivateFn, NavigationCancel, NavigationEnd, NavigationError, NavigationSkipped, Router,
} from '@angular/router';
import { PRICING_ENABLED } from '../shared/site';

/** The pages of the header, in its order: the curtain numbers them the same way. */
export const NAV_PAGES = [
  { path: '/', label: $localize`:Header link:Predictor` },
  { path: '/satellites', label: $localize`:Header link:Satellites` },
  { path: '/alerts', label: $localize`:Header link:Alerts` },
  { path: '/developers', label: $localize`:Header link:Developers` },
  { path: '/pricing', label: $localize`:Header link:Pricing` },
  { path: '/methodology', label: $localize`:Header link:Methodology` },
  { path: '/status', label: $localize`:Header link:Status` },
].filter(page => PRICING_ENABLED || page.path !== '/pricing');

/** Pages that are reachable but not in the header: named, not numbered. */
const OTHER_PAGES: Record<string, string> = { legal: $localize`Legal`, satellite: $localize`Satellite`, starlink: $localize`Starlink` };

/** "04 — Developers" for a header page, "Satellite" for a detail page, "Page" otherwise. */
export function pageLabel(url: string): string {
  const segments = url.split(/[?#]/)[0].split('/').filter(Boolean);
  if (segments[0] === 'satellites' && segments.length > 1) return OTHER_PAGES['satellite'];
  const path = '/' + (segments[0] ?? '');
  const index = NAV_PAGES.findIndex(page => page.path === path);
  if (index >= 0) return `${String(index + 1).padStart(2, '0')} — ${NAV_PAGES[index].label}`;
  return OTHER_PAGES[segments[0] ?? ''] ?? $localize`Page`;
}

export interface CurtainElements {
  readonly curtain: HTMLElement;
  readonly ring: HTMLElement;
  readonly label: HTMLElement;
  readonly bar: HTMLElement;
  readonly main: HTMLElement;
}

const COVER_EASE = 'cubic-bezier(0.76, 0, 0.24, 1)';
const ENTER_EASE = 'cubic-bezier(0.22, 1, 0.36, 1)';
/** How long the curtain holds after the new page is in place, so the label can be read. */
const HOLD_MS = 320;
/** A click older than this did not start the navigation: the curtain opens from the centre. */
const CLICK_FRESH_MS = 1000;

type Phase = 'idle' | 'covering' | 'covered' | 'revealing';

/**
 * The page change: a curtain opens from the click, holds on the name of the next page
 * while the router swaps it in, then wipes upwards off the new page.
 *
 * A guard rather than the View Transitions API: the curtain is neither the old page nor
 * the new one, and a guard is the one place the router waits for. The navigation goes on
 * only once the page is covered, so a lazy chunk loads behind the curtain, never in view.
 *
 * Nothing here is needed to navigate. Without the curtain on screen, without the Web
 * Animations API, or with reduced motion asked for, the guard lets the router through at
 * once and the page swaps instantly.
 */
@Injectable({ providedIn: 'root' })
export class RouteTransition {
  private elements?: CurtainElements;
  private phase: Phase = 'idle';
  private settled = false;
  private lastClick?: { x: number; y: number; at: number };
  private running: Animation[] = [];

  constructor() {
    const router = inject(Router);
    const document = inject(DOCUMENT);
    const subscription = router.events.subscribe(event => {
      if (event instanceof NavigationEnd || event instanceof NavigationCancel ||
          event instanceof NavigationError || event instanceof NavigationSkipped) {
        this.onSettled(event instanceof NavigationEnd ? event.urlAfterRedirects : undefined);
      }
    });

    // Capture phase: this runs before RouterLink's own handler, so it can both remember
    // where the click landed and swallow clicks while a transition is running.
    const onClick = (event: MouseEvent): void => {
      if (this.phase !== 'idle') {
        event.preventDefault();
        event.stopPropagation();
        return;
      }
      if (event.clientX || event.clientY) this.lastClick = { x: event.clientX, y: event.clientY, at: Date.now() };
    };
    document.addEventListener('click', onClick, true);
    inject(DestroyRef).onDestroy(() => {
      subscription.unsubscribe();
      document.removeEventListener('click', onClick, true);
    });
  }

  attach(elements: CurtainElements): void {
    this.elements = elements;
  }

  detach(): void {
    this.elements = undefined;
  }

  /**
   * Covers the page, and resolves once it is covered. False drops a navigation that
   * arrives while another is still behind the curtain.
   */
  async cover(url: string): Promise<boolean> {
    if (this.phase !== 'idle') return false;
    const elements = this.elements;
    if (!elements || !canAnimate(elements.curtain)) return true;

    this.phase = 'covering';
    this.settled = false;
    const { curtain, ring, label, bar, main } = elements;
    const click = this.lastClick && Date.now() - this.lastClick.at < CLICK_FRESH_MS ? this.lastClick : undefined;
    const x = click?.x ?? innerWidth / 2;
    const y = click?.y ?? innerHeight / 2;
    const radius = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));

    label.textContent = pageLabel(url);
    curtain.style.display = 'block';
    ring.style.display = 'block';
    ring.style.left = `${x}px`;
    ring.style.top = `${y}px`;

    const covering = curtain.animate(
      [{ clipPath: `circle(0px at ${x}px ${y}px)` }, { clipPath: `circle(${radius}px at ${x}px ${y}px)` }],
      { duration: 600, easing: COVER_EASE, fill: 'forwards' },
    );
    this.running = [
      covering,
      ring.animate(
        [{ transform: 'scale(0.2)', opacity: 1 }, { transform: `scale(${radius / 10})`, opacity: 0 }],
        { duration: 700, easing: COVER_EASE, fill: 'forwards' },
      ),
      bar.animate([{ transform: 'scaleX(0)' }, { transform: 'scaleX(1)' }], { duration: 900, easing: 'ease-in-out', fill: 'forwards' }),
      main.animate(
        [{ transform: 'scale(1)', filter: 'blur(0px)' }, { transform: 'scale(0.96)', filter: 'blur(6px)' }],
        { duration: 600, easing: COVER_EASE },
      ),
    ];

    await covering.finished.catch(() => undefined);
    this.phase = 'covered';
    // The navigation may have been cancelled while the curtain was closing.
    if (this.settled) void this.reveal(undefined);
    return true;
  }

  private onSettled(url: string | undefined): void {
    if (this.phase === 'covering') this.settled = true;
    else if (this.phase === 'covered') void this.reveal(url);
  }

  private async reveal(url: string | undefined): Promise<void> {
    const elements = this.elements;
    this.phase = 'revealing';
    // A fragment is the router's to scroll to; anything else opens at its top.
    if (url !== undefined && !url.includes('#')) window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    await new Promise(resolve => setTimeout(resolve, HOLD_MS));
    if (!elements) return this.finish();

    const { curtain, main } = elements;
    const wiping = curtain.animate(
      [{ clipPath: 'inset(0 0 0 0)' }, { clipPath: 'inset(0 0 100% 0)' }],
      { duration: 650, easing: COVER_EASE, fill: 'forwards' },
    );
    // Not kept in `running`: the page's entrance outlasts the wipe and ends on its own.
    main.animate(
      [{ transform: 'translateY(60px)', opacity: 0 }, { transform: 'translateY(0)', opacity: 1 }],
      { duration: 800, delay: 150, easing: ENTER_EASE, fill: 'backwards' },
    );
    this.running.push(wiping);
    await wiping.finished.catch(() => undefined);
    this.finish();
  }

  private finish(): void {
    if (this.elements) {
      this.elements.curtain.style.display = 'none';
      this.elements.ring.style.display = 'none';
    }
    for (const animation of this.running) animation.cancel();
    this.running = [];
    this.phase = 'idle';
  }
}

function canAnimate(element: HTMLElement): boolean {
  if (typeof element.animate !== 'function' || typeof window.matchMedia !== 'function') return false;
  return !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Holds every navigation after the first until the curtain covers the page. */
export const coverTransition: CanActivateFn = (_route, state) => {
  if (!inject(Router).navigated) return true;
  return inject(RouteTransition).cover(state.url);
};
