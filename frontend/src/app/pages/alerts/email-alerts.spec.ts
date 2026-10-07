import { describe, beforeEach, it, expect } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { EmailAlerts } from './email-alerts';

describe('EmailAlerts', () => {
  beforeEach(() => TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
  }).compileComponents());

  function render(open = true) {
    const fixture = TestBed.createComponent(EmailAlerts);
    fixture.componentRef.setInput('noradId', 25544);
    fixture.componentRef.setInput('lat', 45.7578);
    fixture.componentRef.setInput('lon', 4.832);
    TestBed.tick();
    TestBed.inject(HttpTestingController).expectOne('/api/alerts').flush({ enabled: open });
    TestBed.tick();
    return fixture.nativeElement as HTMLElement;
  }

  function submit(page: HTMLElement, email: string) {
    const input = page.querySelector('input[type=email]') as HTMLInputElement;
    input.value = email;
    input.dispatchEvent(new Event('input'));
    page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    TestBed.tick();
  }

  it('sends the place, the thresholds and the time zone, then says to check the inbox', () => {
    const page = render();
    const sky = page.querySelectorAll('select')[1] as HTMLSelectElement;
    sky.value = '50';
    sky.dispatchEvent(new Event('change'));
    submit(page, ' ada@example.org ');

    const request = TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/alerts' && r.method === 'POST');
    expect(request.request.body).toMatchObject({
      email: 'ada@example.org', noradId: 25544, lat: 45.7578, lon: 4.832,
      minElevationDeg: 30, maxCloudPercent: 50, maxMagnitude: null, locale: 'en',
    });
    expect(request.request.body.timeZone).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    request.flush({ status: 'pending' }, { status: 202, statusText: 'Accepted' });
    TestBed.tick();

    expect(page.textContent).toContain('Check your inbox.');
    expect(page.textContent).toContain('ada@example.org');
  });

  it('shows no form while the backend takes no sign-ups', () => {
    const page = render(false);

    expect(page.querySelector('form')).toBeNull();
    expect(page.textContent).toContain('coming soon');
  });

  it('refuses an address that cannot be one without asking the server', () => {
    const page = render();
    submit(page, 'ada@example');

    TestBed.inject(HttpTestingController).expectNone((r) => r.method === 'POST');
    expect(page.querySelector('[role=alert]')?.textContent).toContain('valid e-mail address');
  });

  it('says plainly when reminders are not open on the server', () => {
    const page = render();
    submit(page, 'ada@example.org');

    TestBed.inject(HttpTestingController).expectOne((r) => r.method === 'POST').flush(
      { type: 'https://github.com/warlaxx/sat-pass-predictor/errors/alerts-unavailable', title: 'Reminders unavailable', status: 503 },
      { status: 503, statusText: 'Service Unavailable' });
    TestBed.tick();

    expect(page.querySelector('[role=alert]')?.textContent).toContain('not open yet');
    expect(page.querySelector('form')).not.toBeNull();
  });
});
