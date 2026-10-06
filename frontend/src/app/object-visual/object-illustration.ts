import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ObjectFamily, familyLabel } from './object-family';

/**
 * A line drawing of an object's kind (ABD-46), for the objects without a free
 * photograph. Drawn, not photographed, and captioned as such by the caller: it shows what
 * kind of thing separated, never this particular object.
 *
 * Inline SVG in the site's own strokes: no request, no layout shift, legible on the dark
 * panels, and a few hundred bytes each. 4:3, like the photographs it stands in for.
 */
@Component({
  selector: 'app-object-illustration',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 160 120" role="img" [attr.aria-label]="label()" preserveAspectRatio="xMidYMid meet">
      <path class="orbit" d="M-8 104 Q80 58 168 98" />
      <circle class="star" cx="22" cy="20" r="1" /><circle class="star" cx="136" cy="16" r="1.2" />
      <circle class="star" cx="146" cy="62" r="0.8" /><circle class="star" cx="12" cy="76" r="0.8" />
      @switch (family()) {
        @case ('satellite') {
          <g class="body">
            <rect x="14" y="50" width="44" height="20" rx="1.5" />
            <path d="M28.7 50 V70 M43.3 50 V70 M14 60 H58" class="fine" />
            <rect x="102" y="50" width="44" height="20" rx="1.5" />
            <path d="M116.7 50 V70 M131.3 50 V70 M102 60 H146" class="fine" />
            <path d="M58 60 H68 M92 60 H102" />
            <rect x="68" y="46" width="24" height="28" rx="2" />
            <path d="M80 46 V38" />
          </g>
          <path class="hi" d="M71 38 Q80 28 89 38 Z" />
        }
        @case ('cubesat') {
          <g class="body">
            <path d="M62 46 L72 38 H102 L92 46 Z M92 46 L102 38 V68 L92 76 Z" />
            <rect x="62" y="46" width="30" height="30" />
            <path d="M72 46 V76 M82 46 V76 M62 56 H92 M62 66 H92" class="fine" />
            <rect x="26" y="50" width="30" height="14" rx="1" />
            <path d="M36 50 V64 M46 50 V64" class="fine" />
            <path d="M56 57 H62" />
          </g>
          <path class="hi" d="M102 38 L118 22" />
          <circle class="hi dot" cx="118" cy="22" r="1.8" />
        }
        @case ('stage') {
          <g class="body">
            <ellipse cx="80" cy="22" rx="14" ry="4" />
            <path d="M66 22 V80 M94 22 V80" />
            <path d="M66 80 Q80 86 94 80" />
            <path d="M66 36 Q80 42 94 36 M66 66 Q80 72 94 66" class="fine" />
          </g>
          <path class="hi" d="M73 83 L66 100 Q80 105 94 100 L87 83" />
        }
        @case ('fairing') {
          <g class="body">
            <path transform="rotate(-9 64 100)" d="M80 18 C68 30 64 40 64 52 V100 H80 Z" />
            <path transform="rotate(9 96 100)" d="M80 18 C92 30 96 40 96 52 V100 H80 Z" />
          </g>
          <rect class="hi" x="74" y="62" width="12" height="22" rx="1.5" />
        }
        @case ('motor') {
          <g class="body">
            <circle cx="80" cy="50" r="20" />
            <ellipse cx="80" cy="50" rx="20" ry="6" class="fine" />
          </g>
          <path class="hi" d="M73 69 L66 92 Q80 97 94 92 L87 69" />
        }
        @case ('hardware') {
          <g class="body">
            <ellipse cx="80" cy="42" rx="34" ry="10" />
            <ellipse cx="80" cy="78" rx="24" ry="7" />
            <path d="M46 42 L56 78 M114 42 L104 78" />
            <path d="M58 45 L64 76 M102 45 L96 76" class="fine" />
          </g>
          <g class="hi dot">
            <circle cx="50" cy="40" r="1.6" /><circle cx="66" cy="34" r="1.6" /><circle cx="94" cy="34" r="1.6" />
            <circle cx="110" cy="40" r="1.6" /><circle cx="80" cy="32.5" r="1.6" />
          </g>
        }
        @case ('debris') {
          <g class="body">
            <path d="M58 40 L74 34 L80 46 L68 54 Z" />
            <path d="M92 58 L106 52 L112 62 L100 70 L94 66 Z" />
            <path d="M52 72 L60 68 L64 78 L55 80 Z" />
            <path d="M84 82 L90 80 L91 86 Z" />
          </g>
          <path class="hi" d="M30 30 L46 36 M112 30 L124 24 M118 82 L132 88 M30 90 L42 84" />
          <g class="hi dot">
            <circle cx="86" cy="40" r="1.4" /><circle cx="76" cy="66" r="1.2" /><circle cx="118" cy="46" r="1.2" />
          </g>
        }
        @default {
          <g class="body">
            <ellipse cx="80" cy="60" rx="40" ry="14" class="fine" />
            <circle cx="80" cy="60" r="12" />
          </g>
          <circle class="hi dot" cx="117" cy="54" r="2.4" />
        }
      }
    </svg>
  `,
  styles: `
    :host { color: var(--ink-3); display: block; height: 100%; width: 100%; }
    svg { display: block; height: 100%; width: 100%; }
    path, rect, circle, ellipse { fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.6; vector-effect: non-scaling-stroke; }
    .fine { stroke: var(--ink-4); stroke-width: 1.1; }
    .orbit { stroke: var(--line-strong); stroke-dasharray: 2 5; stroke-width: 1; }
    .star { fill: var(--ink-4); stroke: none; }
    .hi { stroke: var(--accent); }
    .hi.dot, .hi .dot, g.hi.dot circle { fill: var(--accent); stroke: none; }
  `,
})
export class ObjectIllustration {
  readonly family = input.required<ObjectFamily>();
  protected readonly label = computed(() => $localize`:Alternative text of an object's illustration:Illustration: ${familyLabel(this.family())}:kind:`);
}
