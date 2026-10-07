import { describe, beforeEach, it, expect, vi, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ShareButton } from './share-button';

describe('ShareButton', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ShareButton], providers: [provideZonelessChangeDetection()] }).compileComponents();
  });
  afterEach(() => {
    delete (navigator as unknown as Record<string, unknown>)['share'];
    vi.restoreAllMocks();
  });

  async function render(url?: string) {
    const fixture = TestBed.createComponent(ShareButton);
    fixture.componentRef.setInput('title', 'USA 396 released USA 667');
    if (url) fixture.componentRef.setInput('url', url);
    await fixture.whenStable();
    return fixture;
  }

  it('opens the share sheet with the title and the link', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    const fixture = await render('https://www.nextpass.space/separations/S100685');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(share).toHaveBeenCalledWith({ title: 'USA 396 released USA 667', url: 'https://www.nextpass.space/separations/S100685' });
  });

  it('copies the link where there is no share sheet, and says so', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const fixture = await render('https://www.nextpass.space/?norad=25544');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await new Promise((resolve) => setTimeout(resolve));
    await fixture.whenStable();
    expect(writeText).toHaveBeenCalledWith('https://www.nextpass.space/?norad=25544');
    expect(fixture.nativeElement.querySelector('button').textContent).toContain('Link copied');
  });
});
