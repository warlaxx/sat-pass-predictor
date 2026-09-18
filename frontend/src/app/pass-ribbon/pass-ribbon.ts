import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { PassDto } from '../api/passes.model';
import { compassPoint, elevationColour, formatDuration } from '../format';
import { Night, groupIntoNights } from './nights';

/**
 * The window at a glance: one column per night, one bar per pass, height = maximum
 * elevation.
 *
 * <p>The table below says everything this says, and more precisely. What the table cannot
 * do is answer "which evening is worth going outside" without reading nine rows: the
 * shape of the ribbon does it in one look, because a tall bar is a pass that goes near
 * the zenith and an empty column is a night with nothing.
 *
 * <p>Each bar is a real {@code <button>} with {@code aria-pressed} and a label that spells
 * the pass out in words. A div with a click handler would leave the whole view unreachable
 * from the keyboard and silent to a screen reader, and this is the only view of the page a
 * pointer is genuinely faster at.
 */
@Component({
  selector: 'app-pass-ribbon',
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ribbon" role="group" aria-label="Passes by night">
      @for (night of nights(); track night.key) {
        <div class="night">
          <div class="bars">
            @for (pass of night.passes; track pass.aos.instant) {
              <button
                type="button"
                class="bar"
                [style.height.%]="height(pass)"
                [style.background]="colour(pass)"
                [attr.aria-pressed]="pass.aos.instant === selected()"
                [attr.aria-label]="describe(night, pass)"
                [class.selected]="pass.aos.instant === selected()"
                (click)="select.emit(pass.aos.instant)"
              >
                <span class="peak num">{{ pass.culmination.elevationDeg | number: '1.0-0' }}</span>
              </button>
            } @empty {
              <p class="none" aria-label="{{ night.longLabel }}: no pass">&mdash;</p>
            }
          </div>
          <div class="date label" aria-hidden="true">{{ night.label }}</div>
        </div>
      }
    </div>
  `,
  styles: `
    .ribbon {
      display: grid;
      gap: 0.5rem;
      grid-auto-columns: minmax(0, 1fr);
      grid-auto-flow: column;
    }

    .night {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      min-width: 0;
    }

    .bars {
      align-items: end;
      border-bottom: 1px solid var(--line);
      display: flex;
      gap: 3px;
      height: 6.5rem;
      justify-content: center;
    }

    .bar {
      align-items: start;
      border: 0;
      border-radius: 4px 4px 0 0;
      cursor: pointer;
      display: flex;
      flex: 1 1 0;
      justify-content: center;
      /* A grazing pass is 10 degrees out of 90: without a floor its bar is two pixels
         tall and unclickable, which would hide exactly the passes worth warning about. */
      min-height: 10px;
      min-width: 0;
      opacity: 0.68;
      padding: 2px 0 0;
      transition: opacity 120ms ease;
    }

    .bar:hover,
    .bar.selected {
      opacity: 1;
    }

    .bar.selected {
      box-shadow: 0 0 0 1px var(--bg), 0 0 0 2px var(--ink);
    }

    .peak {
      color: #10152a;
      font-size: 0.66rem;
      line-height: 1;
    }

    .none {
      color: var(--ink-3);
      margin: 0 0 0.2rem;
    }

    .date {
      text-align: center;
      white-space: nowrap;
    }

    @media (width < 880px) {
      /* Five nights per row rather than ten columns two characters wide. */
      .ribbon {
        grid-auto-flow: row;
        grid-template-columns: repeat(5, minmax(0, 1fr));
      }

      .bars {
        height: 4.5rem;
      }
    }

    @media (width < 640px) {
      .peak {
        display: none;
      }
    }
  `,
})
export class PassRibbon {
  readonly passes = input.required<readonly PassDto[]>();
  readonly selected = input<string | undefined>(undefined);
  readonly select = output<string>();

  protected readonly nights = computed(() => groupIntoNights(this.passes()));

  /**
   * Height as a share of 90 degrees, so two bars are comparable across nights and across
   * queries. Scaling to the tallest pass of the window would make a mediocre evening look
   * excellent whenever the window holds nothing better.
   */
  protected height(pass: PassDto): number {
    return (pass.culmination.elevationDeg / 90) * 100;
  }

  protected colour(pass: PassDto): string {
    return elevationColour(pass.culmination.elevationDeg);
  }

  protected describe(night: Night, pass: PassDto): string {
    const time = new Date(pass.aos.instant).toLocaleTimeString(undefined, {
      hour: '2-digit',
      minute: '2-digit',
    });
    const peak = Math.round(pass.culmination.elevationDeg);
    return `${night.longLabel}, rises at ${time} toward ${compassPoint(pass.aos.azimuthDeg)},`
      + ` peaks at ${peak} degrees toward ${compassPoint(pass.culmination.azimuthDeg)},`
      + ` lasts ${formatDuration(pass.durationSeconds)}`;
  }
}
