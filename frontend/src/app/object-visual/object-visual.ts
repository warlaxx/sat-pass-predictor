import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { SpaceObject } from '../api/separations.model';
import { familyLabel, objectFamily } from './object-family';
import { ObjectIllustration } from './object-illustration';

/** Where the backend's thumbnails live, and the path this site serves them under. */
const COMMONS_HOST = 'https://upload.wikimedia.org/';
export const COMMONS_PATH = '/commons-images/';

/**
 * An object's picture (ABD-47): its photograph when a free one exists, an illustration of
 * its kind otherwise, in a fixed 4:3 frame that holds its place while anything loads.
 *
 * The photograph is fetched from this site (`/commons-images/`, rewritten to Wikimedia's
 * servers by vercel.json and nginx) rather than by the browser from a third party, and it
 * carries the credit its licence asks for: author, licence and the file's page.
 * If it fails to load, the illustration takes its place rather than a broken image.
 */
@Component({
  selector: 'app-object-visual',
  imports: [ObjectIllustration],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.thumb]': "variant() === 'thumb'" },
  template: `
    @let shown = photo();
    <figure>
      <div class="frame">
        @if (shown) {
          <img [src]="shown.src" [attr.width]="shown.width" [attr.height]="shown.height" [alt]="alt()"
               loading="lazy" decoding="async" (error)="failed.set(shown.url)" />
        } @else {
          <app-object-illustration [family]="family()" />
          @if (variant() === 'card') {
            <span class="badge" [title]="kind()" i18n="Marks a drawing that is not a photograph of the object">Illustration</span>
          }
        }
      </div>
      @if (variant() === 'card' && shown) {
        <figcaption>
          {{ byline() }}
          · @if (shown.licenceUrl) { <a [href]="shown.licenceUrl" rel="noopener license">{{ shown.licence }}</a> } @else { {{ shown.licence }} }
          · <a [href]="shown.sourceUrl" rel="noopener">Wikimedia Commons</a>
        </figcaption>
      } @else if (shown) {
        <a class="credit" [href]="shown.sourceUrl" rel="noopener" [attr.aria-label]="credit()" [title]="credit()">©</a>
      }
    </figure>
  `,
  styles: `
    :host { display: block; min-width: 0; }
    figure { margin: 0; position: relative; }
    .frame {
      aspect-ratio: 4 / 3;
      background: radial-gradient(120% 90% at 50% 40%, #11131a 0%, var(--panel) 70%);
      border: 1px solid var(--line-soft);
      border-radius: calc(var(--r-card) - 6px);
      overflow: hidden;
      position: relative;
    }
    img { display: block; height: 100%; object-fit: cover; width: 100%; }
    app-object-illustration { padding: 6% 8%; }
    .badge {
      background: rgb(0 0 0 / 0.55);
      border: 1px solid var(--line);
      border-radius: var(--r-pill);
      color: var(--ink-3);
      font-size: 11px;
      left: 10px;
      letter-spacing: 0.06em;
      padding: 1px 8px;
      position: absolute;
      text-transform: uppercase;
      top: 10px;
    }
    figcaption { color: var(--ink-3); font-size: 12px; line-height: 1.4; margin-top: 8px; overflow-wrap: anywhere; }
    figcaption a { color: var(--ink-2); }
    :host(.thumb) { width: 56px; }
    :host(.thumb) .frame { border-radius: 6px; }
    :host(.thumb) app-object-illustration { padding: 2px; }
    .credit {
      background: rgb(0 0 0 / 0.6);
      border-radius: 4px;
      bottom: 2px;
      color: var(--ink-2);
      font-size: 10px;
      line-height: 1;
      padding: 2px 3px;
      position: absolute;
      right: 2px;
      text-decoration: none;
    }
  `,
})
export class ObjectVisual {
  readonly object = input.required<SpaceObject>();
  /** A card with its caption, or a 56-pixel thumbnail for a table row. */
  readonly variant = input<'card' | 'thumb'>('card');

  /** The URL that failed to load, so that another object's photograph is still tried. */
  protected readonly failed = signal<string | null>(null);

  protected readonly family = computed(() => objectFamily(this.object()));
  protected readonly kind = computed(() => familyLabel(this.family()));

  protected readonly photo = computed(() => {
    const image = this.object().image;
    if (!image || !image.url.startsWith(COMMONS_HOST) || image.url === this.failed()) return undefined;
    return { ...image, src: COMMONS_PATH + image.url.slice(COMMONS_HOST.length) };
  });

  protected readonly alt = computed(() => {
    const object = this.object();
    return $localize`:Alternative text of an object's photograph:Photograph: ${object.name ?? object.id}:name:`;
  });

  /** The start of the credit: who took or made the picture, when Commons says. */
  protected readonly byline = computed(() => {
    const author = this.photo()?.author;
    return author
      ? $localize`:Credit under a photograph, its author:Photo: ${author}:author:`
      : $localize`:Credit under a photograph whose author is not recorded:Photo`;
  });

  protected readonly credit = computed(() => {
    const image = this.photo();
    return image
      ? $localize`:Credit of a small photograph, as a tooltip:Photo: ${image.author ?? '?'}:author:, ${image.licence}:licence:, Wikimedia Commons`
      : '';
  });
}
