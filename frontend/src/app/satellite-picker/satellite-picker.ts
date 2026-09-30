import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, catchError, debounceTime, map, of, switchMap, tap } from 'rxjs';
import { SatelliteMatch, SatellitesApi, asNoradId } from '../api/satellites.service';

/** Long enough to skip the intermediate keystrokes of a word, short enough to feel live. */
export const SEARCH_DEBOUNCE_MS = 250;

let nextId = 0;

type Lookup = { query: string; matches: SatelliteMatch[]; error?: string };

/**
 * A satellite, by name or by NORAD number.
 *
 * Two modes, because the two places that use it want different things from a number:
 * - `value` (the main form): the field *is* the satellite. Digits are a NORAD number and
 *   are emitted as typed, without a lookup, so the original workflow costs nothing. Any
 *   other text emits `null` until a suggestion is chosen: a half-typed name must never
 *   leave the previous satellite silently in the query.
 * - `add` (the comparison): the field only proposes. Nothing is emitted until a
 *   suggestion is chosen, and the field is cleared for the next one.
 *
 * The listbox follows the WAI-ARIA combobox pattern: arrows move, Enter chooses, Escape
 * closes, and the options are announced through `aria-activedescendant`.
 */
@Component({
  selector: 'app-satellite-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './satellite-picker.html',
  styleUrl: './satellite-picker.scss',
})
export class SatellitePicker implements OnInit {
  readonly mode = input<'value' | 'add'>('value');
  readonly initial = input<string>('');
  readonly label = input.required<string>();
  readonly describedBy = input<string | null>(null);
  readonly placeholder = input<string>('ISS, HST, 25544…');

  /** Value mode only: the NORAD number the text currently stands for, or null. */
  readonly changed = output<number | null>();
  /** A suggestion was chosen, in either mode. */
  readonly picked = output<SatelliteMatch>();

  private readonly api = inject(SatellitesApi);
  private readonly queries = new Subject<string>();

  protected readonly id = `satellite-picker-${nextId++}`;
  protected readonly text = signal('');
  protected readonly lookup = signal<Lookup | undefined>(undefined);
  protected readonly searching = signal(false);
  protected readonly open = signal(false);
  protected readonly active = signal(-1);
  protected readonly matches = computed(() => this.lookup()?.matches ?? []);
  protected readonly activeId = computed(() =>
    this.open() && this.active() >= 0 ? `${this.id}-option-${this.active()}` : null);

  constructor() {
    this.queries.pipe(
      tap(() => this.searching.set(true)),
      debounceTime(SEARCH_DEBOUNCE_MS),
      // switchMap cancels the request of a query the user has already typed past.
      switchMap(query => this.api.search(query).pipe(
        map(response => ({ query, matches: response.results }) as Lookup),
        catchError((error: HttpErrorResponse) => of<Lookup>({ query, matches: [],
          error: typeof error.error?.detail === 'string' ? error.error.detail
            : 'Name search is unavailable. Type the NORAD number instead.',
        })),
      )),
      takeUntilDestroyed(inject(DestroyRef)),
    ).subscribe(lookup => {
      // A response for text the field no longer holds, or that lands after the list was
      // closed, must never become choosable: it would pick a satellite for another query.
      if (!this.open() || lookup.query !== this.text().trim()) return;
      this.searching.set(false);
      this.lookup.set(lookup);
      this.active.set(lookup.matches.length ? 0 : -1);
    });
  }

  ngOnInit(): void {
    this.text.set(this.initial());
  }

  protected onInput(value: string): void {
    this.text.set(value);
    // The suggestions on screen belong to the previous text; Enter must not choose one.
    this.lookup.set(undefined);
    this.active.set(-1);
    const id = asNoradId(value);
    const searchable = id !== undefined || value.trim().replace(/[^a-z0-9]/gi, '').length >= 2;

    if (this.mode() === 'value') {
      this.changed.emit(id ?? null);
      if (id !== undefined) {
        // A number is already an answer; nothing to look up.
        this.close();
        return;
      }
    }
    if (!searchable) {
      this.close();
      return;
    }
    this.open.set(true);
    this.queries.next(value.trim());
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.matches().length;
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp':
        if (!count) return;
        event.preventDefault();
        this.open.set(true);
        this.active.update(index => (index + (event.key === 'ArrowDown' ? 1 : count - 1)) % count);
        return;
      case 'Enter':
        if (this.open() && this.lookup()?.query === this.text().trim()
            && this.active() >= 0 && this.active() < count) {
          // Choosing, not submitting the surrounding form.
          event.preventDefault();
          this.choose(this.matches()[this.active()]);
        }
        return;
      case 'Escape':
        if (this.open()) {
          event.preventDefault();
          this.close();
        }
        return;
    }
  }

  protected choose(match: SatelliteMatch): void {
    this.close();
    this.picked.emit(match);
    if (this.mode() === 'value') {
      this.text.set(match.name);
      this.changed.emit(match.noradId);
    } else {
      this.text.set('');
    }
  }

  protected close(): void {
    this.open.set(false);
    this.active.set(-1);
    this.searching.set(false);
    this.lookup.set(undefined);
  }
}
