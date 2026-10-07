import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';

/**
 * Settles a page whose pass table asks for the cloud cover (ABD-36). The request only
 * starts once the table renders, and an unanswered one keeps the page from ever being
 * stable: it is answered here as MET Norway down, which the table must survive anyway.
 */
export async function settleAnsweringClouds(fixture: ComponentFixture<unknown>): Promise<void> {
  const http = TestBed.inject(HttpTestingController);
  for (let round = 0; round < 5; round++) {
    TestBed.tick();
    await Promise.resolve();
    for (const request of http.match((r) => r.url.startsWith('/api/weather/'))) {
      request.flush({ title: 'Weather unavailable', status: 503 }, { status: 503, statusText: 'Service Unavailable' });
    }
  }
  await fixture.whenStable();
}
