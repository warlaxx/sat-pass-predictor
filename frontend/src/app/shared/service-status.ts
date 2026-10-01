import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { timeout } from 'rxjs';

/** The overall state the status page computes; the header shows the same one. */
export type ServiceState = 'checking' | 'ok' | 'slow' | 'down';

/** Above this, an answer is a cold start rather than a healthy instance (as on /status). */
const SLOW_MS = 5_000;

/**
 * The one-line status in the header.
 *
 * It asks the actuator health once per visit - the probe the status page uses, which
 * costs nothing against the anonymous quota - and it takes the status page's fuller
 * answer whenever that page has run. As a side effect, the probe wakes a sleeping
 * backend while the visitor is still reading, before they ask for a prediction.
 */
@Injectable({ providedIn: 'root' })
export class ServiceStatus {
  private readonly http = inject(HttpClient);
  private probed = false;
  readonly state = signal<ServiceState>('checking');

  probe(): void {
    if (this.probed) return;
    this.probed = true;
    const start = performance.now();
    this.http.get<{ status?: string }>('/status-probe/health').pipe(timeout(60_000)).subscribe({
      next: body => {
        if (body?.status !== 'UP') this.state.set('down');
        else this.state.set(performance.now() - start > SLOW_MS ? 'slow' : 'ok');
      },
      error: () => this.state.set('down'),
    });
  }

  /** The status page's verdict, which also measured the catalogue. */
  report(state: ServiceState): void {
    this.probed = true;
    this.state.set(state);
  }
}
