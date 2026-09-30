import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';

/**
 * A snippet a reader will paste: shown as it is, copied as it is.
 *
 * No syntax highlighting. A highlighter is a dependency, and a curl line or a dozen lines
 * of JSON read perfectly well in one colour; what matters is that the copied text is
 * exactly the displayed text, which a hand-coloured copy would not guarantee.
 */
@Component({
  selector: 'app-code-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="code">
      <div class="code-bar">
        <span>{{ label() }}</span>
        <button type="button" class="btn ghost small" (click)="copy()">{{ copied() ? 'Copied' : 'Copy' }}</button>
      </div>
      <pre><code>{{ code() }}</code></pre>
    </div>
  `,
})
export class CodeBlock {
  readonly label = input.required<string>();
  readonly code = input.required<string>();
  protected readonly copied = signal(false);

  protected copy(): void {
    // The Clipboard API needs a secure context; without one the button simply says nothing.
    navigator.clipboard?.writeText(this.code()).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1600);
    }, () => undefined);
  }
}
