import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { PassDto } from '../api/passes.model';
import { compassPoint, elevationColour, formatDuration, isRemarkable, shadowEntry, utcOffsetLabel } from '../format';

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
      <caption class="visually-hidden">
        Every pass of the window, in words. This is the text equivalent of the sky chart
        and the globe: local times, maximum elevation, duration, and the direction to look
        at each phase.
      </caption>
      <thead>
        <tr>
          <th scope="col">Night</th>
          <th scope="col">Rise <span class="zone">{{ zone() }}</span></th>
          <th scope="col">Az<span class="visually-hidden">imuth at rise</span></th>
          <th scope="col">Peak</th>
          <th scope="col">Elev.<span class="visually-hidden"> maximum</span></th>
          <th scope="col">Az<span class="visually-hidden">imuth at peak</span></th>
          <th scope="col">Set</th>
          <th scope="col">Az<span class="visually-hidden">imuth at set</span></th>
          <th scope="col">Duration</th>
          <th scope="col"><span class="visually-hidden">Remarks</span></th>
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
            (keydown.space)="$event.preventDefault(); select.emit(pass.aos.instant)"
          >
            <td class="night">{{ pass.aos.instant | date: 'EEE d MMM' }}</td>
            <td class="num">{{ pass.aos.instant | date: 'HH:mm:ss' }}</td>
            <td class="num dim">{{ point(pass.aos.azimuthDeg) }}</td>
            <td class="num">{{ pass.culmination.instant | date: 'HH:mm:ss' }}</td>
            <td class="num">
              <span class="elev"><i [style.background]="colour(pass)"></i>{{ pass.culmination.elevationDeg | number: '1.0-0' }}&deg;</span>
            </td>
            <td class="num dim">{{ point(pass.culmination.azimuthDeg) }}</td>
            <td class="num">{{ pass.los.instant | date: 'HH:mm:ss' }}</td>
            <td class="num dim">{{ point(pass.los.azimuthDeg) }}</td>
            <td class="num">{{ duration(pass) }}</td>
            <td class="remarks">
              @if (shadow(pass); as instant) {
                <span class="chip num lit">shadow at {{ instant | date: 'HH:mm' }}</span>
              }
              @if (remarkable(pass)) {
                <span class="chip num hot">remarkable</span>
              }
              @if (pass.durationSeconds < 60) {
                <span class="chip num quiet">grazing &lt; 60 s</span>
              }
            </td>
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
      border: 1px solid var(--line);
      border-radius: var(--r);
      overflow-x: auto;
      /* Contains the visually-hidden spans of the headers: they are absolutely
         positioned, and without this they escape the scroller and widen the page. */
      position: relative;
    }

    table {
      border-collapse: collapse;
      font-size: 14px;
      width: 100%;
    }

    th {
      background: var(--panel);
      border-bottom: 1px solid var(--rule);
      color: var(--ink-3);
      font-size: 13px;
      font-weight: 600;
      padding: 14px;
      text-align: left;
      white-space: nowrap;
    }

    .zone {
      font-weight: 400;
    }

    td {
      border-bottom: 1px solid var(--line-soft);
      height: 50px;
      padding: 0 14px;
      white-space: nowrap;
    }

    tbody tr:last-child td {
      border-bottom: 0;
    }

    tbody tr {
      transition: background-color var(--motion-fast);
      cursor: pointer;
    }

    tbody tr:hover, tbody tr:focus-visible {
      background: var(--panel);
    }

    tbody tr.selected {
      background: rgb(252 61 33 / 10%);
    }

    tbody tr.selected td:first-child {
      box-shadow: inset 3px 0 0 var(--signal);
      font-weight: 600;
    }

    .dim {
      color: var(--ink-2);
    }

    .elev {
      align-items: center;
      display: inline-flex;
      gap: 8px;
    }

    .elev i {
      border-radius: 99px;
      height: 9px;
      width: 9px;
    }

    .chip {
      border: 1px solid currentColor;
      border-radius: 99px;
      display: inline-block;
      font-size: 12px;
      margin-right: 6px;
      padding: 3px 9px;
    }

    .chip.lit { color: var(--lit); }
    .chip.hot { color: var(--hot); }
    .chip.quiet { color: var(--ink-3); }
  `,
})
export class PassTable {
  readonly passes = input.required<readonly PassDto[]>();
  readonly selected = input<string | undefined>(undefined);
  readonly select = output<string>();

  /** The offset of the first pass; the table would not span a DST change unnoticed by it alone. */
  protected readonly zone = computed(() => {
    const first = this.passes()[0];
    return first ? `(${utcOffsetLabel(first.aos.instant)})` : '';
  });

  protected duration(pass: PassDto): string {
    return formatDuration(pass.durationSeconds);
  }

  protected colour(pass: PassDto): string {
    return elevationColour(pass.culmination.elevationDeg);
  }

  protected point(azimuthDeg: number): string {
    return compassPoint(azimuthDeg);
  }

  protected remarkable(pass: PassDto): boolean {
    return isRemarkable(pass.culmination.elevationDeg);
  }

  protected shadow(pass: PassDto): string | undefined {
    return shadowEntry(pass.track);
  }
}
