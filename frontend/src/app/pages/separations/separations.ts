import { ChangeDetectionStrategy, Component, LOCALE_ID, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { DecimalPipe, isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { ProblemDetail } from '../../api/passes.model';
import { SeparationKind, SeparationsResponse } from '../../api/separations.model';
import { Reveal } from '../../motion/reveal';
import { countUsage } from '../../shared/usage';
import { EventRow, eventRow } from './separation-format';

/** Enough for several months of events; the list is grouped by month below. */
export const SEPARATIONS_LIMIT = 60;

type Filter = 'ALL' | SeparationKind;

/** Height of one event in the month chart, in pixels. */
const BAR_UNIT = 32;

/**
 * What separated in orbit: the newest events, grouped by month, with this year's count
 * by month above them. The text is prerendered; the events are the browser's, like
 * every live figure on the site.
 */
@Component({
  selector: 'app-separations',
  imports: [DecimalPipe, RouterLink, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './separations.html',
  styleUrl: './separations.scss',
})
export class SeparationsPage {
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly locale = inject(LOCALE_ID);

  protected readonly resource = httpResource<SeparationsResponse>(() =>
    this.browser ? { url: '/api/separations', params: { limit: SEPARATIONS_LIMIT } } : undefined);
  protected readonly response = computed(() => (this.resource.hasValue() ? this.resource.value() : undefined));

  protected readonly filter = signal<Filter>('ALL');
  protected readonly filters = computed(() => {
    const events = this.response()?.events ?? [];
    const count = (kind: Filter) => kind === 'ALL' ? events.length : events.filter((e) => e.kind === kind).length;
    return [
      { key: 'ALL' as Filter, label: $localize`:Separation filter:All`, count: count('ALL') },
      { key: 'RELEASE' as Filter, label: $localize`:Separation filter:Releases`, count: count('RELEASE') },
      { key: 'FRAGMENTATION' as Filter, label: $localize`:Separation filter:Fragmentations`, count: count('FRAGMENTATION') },
    ];
  });

  /** The events shown, grouped under their month in the reader's language. */
  protected readonly months = computed(() => {
    const filter = this.filter();
    const monthName = new Intl.DateTimeFormat(this.locale, { timeZone: 'UTC', month: 'long', year: 'numeric' });
    const groups: { label: string; events: EventRow[] }[] = [];
    for (const event of this.response()?.events ?? []) {
      if (filter !== 'ALL' && event.kind !== filter) continue;
      const label = event.date.at ? monthName.format(new Date(event.date.at)) : event.date.text;
      let group = groups.find((g) => g.label === label);
      if (!group) groups.push(group = { label, events: [] });
      group.events.push(eventRow(event, this.locale));
    }
    return groups;
  });

  /** This year's events by month, scaled for the chart. */
  protected readonly bars = computed(() => {
    const stats = this.response()?.stats;
    if (!stats) return [];
    const monthName = new Intl.DateTimeFormat(this.locale, { timeZone: 'UTC', month: 'short' });
    return stats.byMonth.map((month) => ({
      ...month,
      label: monthName.format(new Date(`${month.month}-01T00:00:00Z`)),
      total: month.releases + month.fragmentations,
      releaseHeight: month.releases * BAR_UNIT,
      fragmentationHeight: month.fragmentations * BAR_UNIT,
    }));
  });
  protected readonly chartHeight = computed(() =>
    Math.max(4, ...this.bars().map((bar) => bar.total)) * BAR_UNIT);
  protected readonly hovered = signal<string | undefined>(undefined);
  protected readonly hoverText = computed(() => {
    const bar = this.bars().find((b) => b.month === this.hovered());
    if (!bar) return undefined;
    return $localize`:Month detail in the separation chart:${bar.label}:month: ${this.response()?.stats.year}:year:: ${bar.releases}:releases: releases, ${bar.fragmentations}:fragmentations: fragmentations`;
  });

  protected readonly updatedAt = computed(() => {
    const at = this.response()?.updatedAt;
    return at ? new Intl.DateTimeFormat(this.locale, { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(at)) : undefined;
  });

  protected readonly problem = computed<ProblemDetail | undefined>(() => {
    const error = this.resource.error();
    if (!error) return undefined;
    const body = error instanceof HttpErrorResponse ? error.error : undefined;
    if (body && typeof body === 'object' && 'title' in body) return body as ProblemDetail;
    return { type: 'about:blank', title: $localize`The backend could not be reached`, status: 0, detail: $localize`The server may be starting. Please try again shortly.` };
  });

  protected choose(filter: Filter): void {
    this.filter.set(filter);
  }

  protected reload(): void {
    this.resource.reload();
  }

  protected countOpen(): void {
    countUsage('list-open-event');
  }

  /** Counted when the table opens, not when it closes again. */
  protected countTable(details: HTMLDetailsElement): void {
    if (details.open) countUsage('list-show-table');
  }
}
