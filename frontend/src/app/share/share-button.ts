import { ChangeDetectionStrategy, Component, DOCUMENT, inject, input, signal } from '@angular/core';

/**
 * "Share" (ABD-33): the system's share sheet where there is one (phones, Safari, Edge),
 * the link copied to the clipboard elsewhere, and said so. The link is the page's own
 * address unless `url` names another, so what is shared reopens what the reader sees.
 */
@Component({
  selector: 'app-share-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="btn outline" [class.small]="small()" (click)="share()">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M12 3v12M7 8l5-5 5 5M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6" stroke="currentColor" stroke-width="1.7"
              stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      @if (copied()) {<ng-container i18n>Link copied</ng-container>} @else {<ng-container i18n>Share</ng-container>}
    </button>
    <span class="visually-hidden" role="status">@if (copied()) {<ng-container i18n>Link copied</ng-container>}</span>
  `,
  styles: `
    :host { display: inline-flex; }
    button { align-items: center; display: inline-flex; gap: 8px; }
  `,
})
export class ShareButton {
  private readonly document = inject(DOCUMENT);

  /** What a share sheet shows above the link. */
  readonly title = input.required<string>();
  /** The address to share; the page's own by default. */
  readonly url = input<string | undefined>(undefined);
  readonly small = input(false);

  protected readonly copied = signal(false);

  protected async share(): Promise<void> {
    const url = this.url() ?? this.document.location.href;
    const navigator = this.document.defaultView?.navigator;
    if (navigator?.share) {
      try {
        await navigator.share({ title: this.title(), url });
        return;
      } catch (error) {
        // Cancelled by the reader: nothing to do. Refused by the browser: copy instead.
        if ((error as DOMException).name === 'AbortError') return;
      }
    }
    try {
      await navigator?.clipboard.writeText(url);
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2500);
    } catch {
      // No clipboard either (an insecure context): show the address to copy by hand.
      this.document.defaultView?.prompt($localize`:Fallback when nothing can copy the link:Copy this link`, url);
    }
  }
}
