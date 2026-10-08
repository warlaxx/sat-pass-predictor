import { ChangeDetectionStrategy, Component, PLATFORM_ID, inject, input, signal } from '@angular/core';
import { DecimalPipe, isPlatformBrowser } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { AlertSignup } from '../../api/alerts.model';
import { ProblemDetail } from '../../api/passes.model';
import { currentLanguage } from '../../shared/locale';

/** Cloud cover thresholds offered, in percent: the pass tables' "clear" is 25 or less. */
export const CLOUD_CHOICES = [25, 50] as const;
export const ELEVATION_CHOICES = [20, 30, 45, 60] as const;

type State = 'idle' | 'sending' | 'sent';

/**
 * "E-mail me on the days it is worth going out" (ABD-42): the satellite and the place are
 * the ones chosen above on the page; this form adds the address and what "worth it" means.
 *
 * The backend answers the same whether the address is new or already signed up, so this
 * says "check your inbox" in both cases - and the confirmation e-mail is the only proof
 * that the address belongs to whoever typed it.
 *
 * The form shows only once the backend says it takes sign-ups (`GET /api/alerts`): until
 * reminders are switched on there, the page says they are coming rather than offering a
 * form that would fail. Never asked while prerendering, so the HTML carries the note.
 */
@Component({
  selector: 'app-email-alerts',
  imports: [DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './email-alerts.html',
  styles: `
    .field select { background: transparent; border: 0; color: var(--ink); font-size: 15px; outline: none; padding: 11px 0; }
    .field select option { background: var(--panel); }
    .w-email { width: min(28ch, 70vw); }
    .done { color: var(--ink); }
  `,
})
export class EmailAlerts {
  readonly noradId = input.required<number>();
  readonly lat = input.required<number>();
  readonly lon = input.required<number>();

  protected readonly clouds = CLOUD_CHOICES;
  protected readonly elevations = ELEVATION_CHOICES;
  protected readonly email = signal('');
  protected readonly minElevation = signal<number>(30);
  protected readonly maxCloud = signal<number>(25);
  /** '' for any brightness; only the ISS has a magnitude NextPass knows. */
  protected readonly maxMagnitude = signal('');
  protected readonly state = signal<State>('idle');
  /** Unknown until the backend answers; 'error' when it could not be asked, which is not "closed". */
  protected readonly open = signal<boolean | 'error' | undefined>(undefined);
  protected readonly failure = signal<string | undefined>(undefined);

  private readonly http = inject(HttpClient);
  private readonly language = currentLanguage();

  constructor() {
    if (isPlatformBrowser(inject(PLATFORM_ID))) this.check();
  }

  protected check(): void {
    this.open.set(undefined);
    this.http.get<{ enabled: boolean }>('/api/alerts').subscribe({
      next: (status) => this.open.set(status.enabled === true),
      error: () => this.open.set('error'),
    });
  }

  protected submit(): void {
    this.failure.set(undefined);
    const email = this.email().trim();
    if (!/^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/.test(email)) {
      this.failure.set($localize`Enter a valid e-mail address.`);
      return;
    }
    const body: AlertSignup = {
      email,
      noradId: this.noradId(),
      lat: this.lat(),
      lon: this.lon(),
      minElevationDeg: this.minElevation(),
      maxCloudPercent: this.maxCloud(),
      maxMagnitude: this.maxMagnitude() === '' ? null : Number(this.maxMagnitude()),
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      locale: this.language,
    };
    this.state.set('sending');
    this.http.post('/api/alerts', body).subscribe({
      next: () => this.state.set('sent'),
      error: (error: HttpErrorResponse) => {
        this.state.set('idle');
        this.failure.set(message(error));
      },
    });
  }

  protected again(): void {
    this.state.set('idle');
    this.email.set('');
  }
}

function message(error: HttpErrorResponse): string {
  const problem = error.error as ProblemDetail | undefined;
  const type = typeof problem?.type === 'string' ? problem.type.split('/').pop() : undefined;
  switch (type) {
    case 'alerts-unavailable': return $localize`E-mail reminders are not open yet. The calendar file above works today.`;
    case 'alerts-limit': return $localize`This address already has five reminders. Unsubscribe from one first, from the link at the bottom of any reminder.`;
    case 'alerts-busy': return $localize`Too many sign-ups today: the day's confirmation e-mails are spent. Please try again tomorrow.`;
    case 'unknown-satellite': return $localize`This satellite is not in the catalogue: choose another one above.`;
    case 'invalid-request': return $localize`Check the address and the place above.`;
  }
  return $localize`The server could not be reached. It may be starting: please try again in a minute.`;
}
