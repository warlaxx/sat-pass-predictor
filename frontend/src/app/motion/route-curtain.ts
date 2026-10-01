import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, inject, input, viewChild } from '@angular/core';
import { RouteTransition } from './route-transition';

/**
 * The curtain drawn between two pages; `RouteTransition` plays it.
 *
 * Hidden and inert until a navigation: `display: none` keeps it out of the accessibility
 * tree and away from the pointer, and the label is the only text it carries.
 */
@Component({
  selector: 'app-route-curtain',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="curtain" #curtain aria-hidden="true">
      <div class="glow"></div>
      <div class="content">
        <svg width="64" height="64" viewBox="0 0 32 32">
          <defs>
            <radialGradient id="brand-glow-curtain">
              <stop offset="0" stop-color="#fff" stop-opacity="0.55" />
              <stop offset="1" stop-color="#fff" stop-opacity="0" />
            </radialGradient>
          </defs>
          <rect width="32" height="32" rx="7" fill="#000" />
          <circle cx="16" cy="16" r="11" fill="none" stroke="#fff" stroke-opacity="0.45" stroke-width="1.6" />
          <circle cx="16" cy="16" r="6" fill="none" stroke="#fff" stroke-opacity="0.22" stroke-width="1.2" />
          <path d="M16 3.2V6" stroke="#fff" stroke-width="1.8" stroke-linecap="round" />
          <path d="M8.2 21.5C10.5 13.5 16 9.5 22.6 11.4" fill="none" stroke="var(--signal)" stroke-width="2.8" stroke-linecap="round" />
          <circle cx="22.6" cy="11.4" r="6" fill="url(#brand-glow-curtain)" />
          <circle cx="22.6" cy="11.4" r="2.5" fill="#fff" />
        </svg>
        <span class="label" #label></span>
        <div class="track"><div class="bar" #bar></div></div>
      </div>
    </div>
    <div class="ring" #ring aria-hidden="true"></div>
  `,
  styles: `
    .curtain { background: var(--bg); display: none; inset: 0; pointer-events: none; position: fixed; z-index: 100; }
    .glow { background: radial-gradient(50% 50% at 50% 50%, rgb(90 103 180 / 22%), transparent 70%); inset: 0; position: absolute; }
    .content {
      align-items: center; display: flex; flex-direction: column; gap: 18px; inset: 0;
      justify-content: center; position: absolute;
    }
    .label {
      color: var(--ink-2); font: 500 13px var(--font-mono); letter-spacing: 0.12em; text-transform: uppercase;
    }
    .track { background: var(--line); height: 1px; overflow: hidden; width: 180px; }
    .bar { background: var(--signal); height: 1px; transform: scaleX(0); transform-origin: left; width: 100%; }
    .ring {
      border: 2px solid var(--signal); border-radius: 50%; box-shadow: 0 0 40px rgb(252 61 33 / 50%);
      display: none; height: 40px; left: 0; margin: -20px 0 0 -20px; pointer-events: none;
      position: fixed; top: 0; width: 40px; z-index: 101;
    }
  `,
})
export class RouteCurtain {
  /** The element that shrinks and blurs away, then rises back in: the page outlet. */
  readonly main = input.required<HTMLElement>();

  private readonly curtain = viewChild.required<ElementRef<HTMLElement>>('curtain');
  private readonly ring = viewChild.required<ElementRef<HTMLElement>>('ring');
  private readonly label = viewChild.required<ElementRef<HTMLElement>>('label');
  private readonly bar = viewChild.required<ElementRef<HTMLElement>>('bar');

  constructor() {
    // Injected now, not at the first navigation: it has to see the click that starts it.
    const transition = inject(RouteTransition);
    afterNextRender(() => transition.attach({
      curtain: this.curtain().nativeElement,
      ring: this.ring().nativeElement,
      label: this.label().nativeElement,
      bar: this.bar().nativeElement,
      main: this.main(),
    }));
    inject(DestroyRef).onDestroy(() => transition.detach());
  }
}
