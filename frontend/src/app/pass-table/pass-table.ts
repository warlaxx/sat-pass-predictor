import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PassDto } from '../api/passes.model';
import { compassPoint, elevationColour, formatDuration } from '../format';

/**
 * The full list of passes, and the accessible equivalent of the two visualisations to
 * come.
 *
 * A sky chart and a globe are pictures; a screen reader gets nothing from either. This
 * table carries the same information in words, which is why its `<caption>` says so
 * rather than repeating the heading above it. Milestones 7 and 8 add the drawings; they
 * do not replace this.
 *
 * Each row is a `<button>`-like cell with `tabindex`, so selection works from the
 * keyboard. The identity of a pass is the instant of its AOS: the API publishes no id,
 * and inventing one in the browser would make it disagree with the next response.
 */
@Component({
  selector: 'app-pass-table',
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="scroller">
    <table>
      <caption>
        Every pass of the window, in words. This is the text equivalent of the sky chart
        and the globe: local time and UTC, duration, maximum elevation, and the direction
        to look at each phase.
      </caption>
      <thead>
        <tr>
          <th scope="col">Rise (local)</th>
          <th scope="col">UTC</th>
          <th scope="col" class="right">Duration</th>
          <th scope="col" class="right">Max elev.</th>
          <th scope="col">Rise az.</th>
          <th scope="col">Peak az.</th>
          <th scope="col">Set az.</th>
        </tr>
      </thead>
      <tbody>
        @for (pass of passes(); track pass.aos.instant) {
          <tr
            tabindex="0"
            [attr.aria-current]="pass.aos.instant === selected() ? 'true' : null"
            [class.selected]="pass.aos.instant === selected()"
            (click)="select.emit(pass.aos.instant)"
            (keydown.enter)="select.emit(pass.aos.instant)"
            (keydown.space)="select.emit(pass.aos.instant)"
          >
            <td class="num">{{ pass.aos.instant | date: 'EEE d MMM HH:mm:ss' }}</td>
            <td class="num dim">{{ pass.aos.instant | date: 'HH:mm:ss' : 'UTC' }}</td>
            <td class="num right">{{ duration(pass) }}</td>
            <td class="num right peak" [style.color]="colour(pass)">
              {{ pass.culmination.elevationDeg | number: '1.0-0' }}&deg;
            </td>
            <td class="num">{{ point(pass.aos.azimuthDeg) }}</td>
            <td class="num">{{ point(pass.culmination.azimuthDeg) }}</td>
            <td class="num">{{ point(pass.los.azimuthDeg) }}</td>
          </tr>
        }
      </tbody>
    </table>
    </div>
  `,
  styles: `
    /*
     * The narrow screens scroll rather than lose columns. This table is the accessible
     * equivalent of two drawings: dropping the azimuths to make it fit would take the
     * information away from exactly the readers who have nothing else.
     */
    .scroller {
      overflow-x: auto;
    }

    table {
      border-collapse: collapse;
      width: 100%;
    }

    caption {
      color: var(--ink-3);
      font-size: 0.82rem;
      padding-bottom: 0.75rem;
      text-align: left;
    }

    th {
      border-bottom: 1px solid var(--line);
      color: var(--ink-3);
      font-family: var(--font-display);
      font-size: 0.72rem;
      font-weight: 600;
      letter-spacing: 0.08em;
      padding: 0.4rem 0.6rem;
      text-align: left;
      text-transform: uppercase;
      white-space: nowrap;
    }

    td {
      border-bottom: 1px solid var(--line-soft);
      padding: 0.5rem 0.6rem;
      white-space: nowrap;
    }

    tbody tr {
      cursor: pointer;
    }

    tbody tr:hover,
    tbody tr.selected {
      background: var(--panel-2);
    }

    tbody tr.selected td:first-child {
      box-shadow: inset 3px 0 0 var(--accent);
    }

    .right {
      text-align: right;
    }

    .dim {
      color: var(--ink-3);
    }

    .peak {
      font-weight: 500;
    }

    @media (width < 720px) {
      .dim,
      th:nth-child(2) {
        display: none;
      }
    }
  `,
})
export class PassTable {
  readonly passes = input.required<readonly PassDto[]>();
  readonly selected = input<string | undefined>(undefined);
  readonly select = output<string>();

  protected duration(pass: PassDto): string {
    return formatDuration(pass.durationSeconds);
  }

  protected colour(pass: PassDto): string {
    return elevationColour(pass.culmination.elevationDeg);
  }

  protected point(azimuthDeg: number): string {
    return compassPoint(azimuthDeg);
  }
}
