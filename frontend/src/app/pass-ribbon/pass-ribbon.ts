import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
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
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ribbon" role="group" aria-label="Passes by night">
      @for (night of nights(); track night.key) {
        <div class="night" [class.current]="hasSelected(night)">
          <div class="bars">
            @for (pass of night.passes; track pass.aos.instant) {
              <button
                type="button"
                class="bar"
                [style.height.%]="height(pass)"
                [style.background]="colour(pass)"
                [attr.aria-pressed]="pass.aos.instant === selected()"
                [attr.aria-label]="describe(night, pass)"
                [attr.title]="night.label + ' · ' + (pass.aos.instant | date: 'HH:mm:ss') + ' · max ' + (pass.culmination.elevationDeg | number: '1.0-0') + '°'"
                [class.selected]="pass.aos.instant === selected()"
                (click)="select.emit(pass.aos.instant)"
              ></button>
            } @empty {
              <p class="none" aria-label="{{ night.longLabel }}: no pass">&ndash;</p>
            }
          </div>
          <div class="date" aria-hidden="true">
            <span class="weekday">{{ night.weekday }}</span>
            <span class="num">{{ night.day }}</span>
          </div>
        </div>
      }
    </div>
    <p class="legend">
      <span>Bar height = maximum elevation</span>
      <span><i style="background: var(--accent-dim)"></i>&lt; 25°</span>
      <span><i style="background: var(--accent)"></i>25–45°</span>
      <span><i style="background: var(--lit)"></i>45–70°</span>
      <span><i style="background: var(--hot)"></i>&gt; 70°</span>
    </p>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }

    .ribbon {
      column-gap: 6px;
      display: grid;
      grid-auto-columns: minmax(0, 1fr);
      grid-auto-flow: column;
      row-gap: 18px;
    }

    .night {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 0;
    }

    .bars {
      align-items: end;
      display: flex;
      gap: 6px;
      height: 120px;
      justify-content: center;
    }

    .bar {
      border: 0;
      border-radius: 2px 2px 0 0;
      cursor: pointer;
      flex: 0 1 14px;
      /* A grazing pass is 10 degrees out of 90: without a floor its bar is two pixels
         tall and unclickable, which would hide exactly the passes worth warning about. */
      min-height: 10px;
      min-width: 4px;
      padding: 0;
      transition: filter 120ms ease;
    }

    .bar:hover {
      filter: brightness(1.2);
    }

    .bar.selected {
      box-shadow: 0 0 0 3px var(--bg), 0 0 0 5px var(--ink);
    }

    .none {
      color: var(--ink-4);
      margin: 0 0 2px;
    }

    .date {
      border-top: 1px solid var(--rule);
      color: var(--ink-3);
      display: flex;
      flex-direction: column;
      font-size: 13px;
      line-height: 1.2;
      padding-top: 7px;
      text-align: center;
    }

    .weekday {
      font-size: 11px;
    }

    .current .date {
      color: var(--ink);
    }

    .legend {
      color: var(--ink-3);
      display: flex;
      flex-wrap: wrap;
      font-size: 12px;
      gap: 8px 18px;
      margin: 0;
    }

    .legend span {
      align-items: center;
      display: inline-flex;
      white-space: nowrap;
    }

    .legend i {
      border-radius: 2px;
      display: inline-block;
      height: 10px;
      margin-right: 6px;
      width: 10px;
    }

    @media (width < 880px) {
      /* Five nights per row rather than ten columns two characters wide. */
      .ribbon {
        grid-auto-flow: row;
        grid-template-columns: repeat(5, minmax(0, 1fr));
      }

      .bars {
        height: 96px;
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

  protected hasSelected(night: Night): boolean {
    return night.passes.some(pass => pass.aos.instant === this.selected());
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
