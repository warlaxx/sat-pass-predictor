import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ConfirmedAlert } from '../../api/alerts.model';

type Action = 'confirm' | 'unsubscribe';
type State = 'ready' | 'working' | 'confirmed' | 'unsubscribed' | 'dead' | 'failed';

/**
 * Where the links of the reminder e-mails land (ABD-42): `/alerts/confirm?token=…` and
 * `/alerts/unsubscribe?token=…`.
 *
 * A button, not an action on load: mail scanners open every link of a message they
 * inspect, and some run its script. A page that confirmed on load would confirm on the
 * scanner's behalf - the very thing the double opt-in exists to prevent - and one that
 * unsubscribed on load would unsubscribe readers who never asked. The one-click
 * unsubscribe of the e-mail client goes straight to the API with a POST instead.
 *
 * Rendered in the browser, never prerendered: the token is the page, and a page with no
 * token is of no use to a search engine (app.routes.server.ts).
 */
@Component({
  selector: 'app-alert-link',
  imports: [DecimalPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'page' },
  templateUrl: './alert-link.html',
})
export class AlertLinkPage {
  private readonly route = inject(ActivatedRoute);
  private readonly http = inject(HttpClient);

  protected readonly action = this.route.snapshot.data['action'] as Action;
  protected readonly token = this.route.snapshot.queryParamMap.get('token') ?? '';
  protected readonly state = signal<State>(this.token ? 'ready' : 'dead');
  protected readonly confirmed = signal<ConfirmedAlert | undefined>(undefined);

  protected run(): void {
    this.state.set('working');
    const params = { token: this.token };
    if (this.action === 'confirm') {
      this.http.post<ConfirmedAlert>('/api/alerts/confirm', null, { params }).subscribe({
        next: (alert) => {
          this.confirmed.set(alert);
          this.state.set('confirmed');
        },
        error: (error: HttpErrorResponse) => this.state.set(error.status === 404 ? 'dead' : 'failed'),
      });
    } else {
      this.http.post('/api/alerts/unsubscribe', null, { params }).subscribe({
        next: () => this.state.set('unsubscribed'),
        error: () => this.state.set('failed'),
      });
    }
  }
}
