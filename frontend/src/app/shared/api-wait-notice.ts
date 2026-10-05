import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ApiWaiting } from '../api/api-retry';

/**
 * "The server is waking up": shown while any page has waited more than five seconds on
 * the API, so a cold start reads as a wait rather than as a page that hangs.
 *
 * Fixed at the bottom of the screen, so it is seen wherever the reader has scrolled. The
 * live region stays in the page, empty, so a screen reader announces the sentence when it
 * appears.
 */
@Component({
  selector: 'app-api-wait-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="slot" role="status">
      @if (slow()) {
        <p class="notice">
          <i class="dot" aria-hidden="true"></i>
          <b i18n>The server is waking up.</b>
          <span i18n>It can take up to 30 seconds; the page will fill in on its own.</span>
        </p>
      }
    </div>
  `,
  styles: `
    .slot {
      bottom: 16px;
      display: flex;
      justify-content: center;
      left: 16px;
      pointer-events: none;
      position: fixed;
      right: 16px;
      z-index: 40;
    }
    .notice {
      align-items: center;
      background: color-mix(in srgb, var(--warn) 10%, var(--panel));
      border: 1px solid color-mix(in srgb, var(--warn) 35%, transparent);
      border-radius: 10px;
      color: var(--ink-2);
      display: flex;
      flex-wrap: wrap;
      font-size: 13px;
      gap: 4px 8px;
      line-height: 1.4;
      margin: 0;
      max-width: 560px;
      padding: 10px 14px;
    }
    b { color: var(--warn); font-weight: 600; }
    .dot {
      animation: pulse 1.4s ease-in-out infinite;
      background: var(--warn);
      border-radius: 50%;
      flex-shrink: 0;
      height: 6px;
      width: 6px;
    }
    @keyframes pulse { 50% { opacity: 0.3; } }
    @media (prefers-reduced-motion: reduce) { .dot { animation: none; } }
  `,
})
export class ApiWaitNotice {
  protected readonly slow = inject(ApiWaiting).slow;
}
