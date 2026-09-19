import { afterNextRender, DestroyRef, Directive, ElementRef, inject } from '@angular/core';

/** One entrance per section. Content stays readable without observer or animation support. */
@Directive({ selector: '[appReveal]' })
export class Reveal {
  constructor() {
    const element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      if (typeof IntersectionObserver === 'undefined' ||
          window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

      const observer = new IntersectionObserver(entries => {
        if (!entries.some(entry => entry.isIntersecting)) return;
        element.classList.add('reveal-enter');
        observer.disconnect();
      }, { threshold: 0.08 });
      observer.observe(element);
      destroyRef.onDestroy(() => observer.disconnect());
    });
  }
}
