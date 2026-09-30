import { ChangeDetectionStrategy, Component, DestroyRef, afterNextRender, computed, inject, input, output, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PassDto } from '../api/passes.model';
import { compassPoint, formatCountdown } from '../format';
import { nextPassState } from './next-pass-state';

/**
 * "Next pass in 2 h 14 min": the answer to the question people actually open the page
 * with, above everything else.
 *
 * The countdown ticks once a second from the browser's clock. It is deliberately not a
 * live region: a screen reader announcing every second would drown the page. A reader
 * who wants the figure reads it; the rise time next to it does not change.
 */
@Component({
  selector: 'app-next-pass',
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (state(); as s) {
      <section class="next" [class.live]="s.kind === 'now'" aria-label="Next pass">
        @switch (s.kind) {
          @case ('now') {
            <span class="pulse" aria-hidden="true"></span>
            <div class="text">
              <p class="headline">Passing now <span class="num">· sets in {{ countdown(s.remainingMs) }}</span></p>
              <p class="detail num">
                Look {{ compass(s.position.azimuthDeg) }} ({{ s.position.azimuthDeg | number: '1.0-0' }}°),
                {{ s.position.elevationDeg | number: '1.0-0' }}° above the horizon · sets {{ s.pass.los.instant | date: 'HH:mm' }}
                towards {{ compass(s.pass.los.azimuthDeg) }}
              </p>
            </div>
            <button type="button" (click)="select.emit(s.pass.aos.instant)">Follow this pass</button>
          }
          @case ('next') {
            <div class="text">
              <p class="headline">Next pass in <span class="num">{{ countdown(s.waitMs) }}</span></p>
              <p class="detail num">
                Rises {{ s.pass.aos.instant | date: 'EEE HH:mm' }} in the {{ compass(s.pass.aos.azimuthDeg) }},
                peaks at {{ s.pass.culmination.elevationDeg | number: '1.0-0' }}° towards {{ compass(s.pass.culmination.azimuthDeg) }}
                @if (s.visibleFrom) {
                  · <span class="lit">potentially visible from {{ s.visibleFrom | date: 'HH:mm' }}</span>
                } @else {
                  · not visible to the eye
                }
              </p>
              @if (s.nextVisible; as visible) {
                <p class="detail num">
                  Next potentially visible pass: <span class="lit">{{ visible.start | date: 'EEE HH:mm' }}</span>,
                  in {{ countdown(visible.waitMs) }}
                  <button type="button" class="link" (click)="select.emit(visible.pass.aos.instant)">show</button>
                </p>
              }
            </div>
            <button type="button" (click)="select.emit(s.pass.aos.instant)">Show this pass</button>
          }
          @case ('over') {
            <div class="text">
              <p class="headline">Every pass of this window has set</p>
              <p class="detail num">The last one set {{ s.last.los.instant | date: 'EEE HH:mm' }}. Compute again for the next ones.</p>
            </div>
          }
        }
      </section>
    }
  `,
  styles: `
    :host { display: block; padding-top: 24px; }
    .next {
      align-items: center; background: var(--panel); border: 1px solid var(--line); border-radius: var(--r);
      display: flex; flex-wrap: wrap; gap: 12px 20px; padding: 18px 22px;
    }
    .next.live { border-color: rgb(87 211 168 / 45%); }
    .text { display: flex; flex: 1 1 320px; flex-direction: column; gap: 4px; }
    p { margin: 0; }
    .headline { color: var(--ink); font: 800 22px var(--font-display); font-stretch: 87.5%; }
    .headline .num { font: 400 20px var(--font-mono); }
    .detail { color: var(--ink-2); font-size: 14px; }
    .lit { color: var(--lit); }
    .pulse {
      animation: pulse 1.6s ease-in-out infinite; background: var(--observer); border-radius: 99px;
      box-shadow: 0 0 0 5px rgb(87 211 168 / 18%); flex-shrink: 0; height: 12px; width: 12px;
    }
    @keyframes pulse { 50% { opacity: 0.35; } }
    button {
      background: transparent; border: 1px solid var(--line-strong); border-radius: 4px; color: var(--ink-2);
      cursor: pointer; font: 500 14px var(--font-body); padding: 8px 14px; white-space: nowrap;
    }
    button:hover { border-color: var(--ink-3); color: var(--ink); }
    button.link { border: 0; color: var(--ink); padding: 0 2px; text-decoration: underline; text-decoration-color: var(--signal); }
  `,
})
export class NextPass {
  readonly passes = input.required<readonly PassDto[]>();
  readonly select = output<string>();

  private readonly now = signal(Date.now());
  protected readonly state = computed(() => nextPassState(this.passes(), this.now()));
  protected readonly compass = compassPoint;
  protected readonly countdown = formatCountdown;

  constructor() {
    const destroyRef = inject(DestroyRef);
    // After render: nothing ticks before the component is on screen, or on a server.
    afterNextRender(() => {
      const timer = setInterval(() => this.now.set(Date.now()), 1000);
      destroyRef.onDestroy(() => clearInterval(timer));
    });
  }
}
