import { Component, input } from '@angular/core';

/**
 * FileGrid logo: a 2 x 2 grid of files where one cell is a lightning bolt, i.e. the power grid.
 * Drawn in SVG with the theme colours (works on both light and dark backgrounds).
 */
@Component({
  selector: 'app-logo',
  template: `
    <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 32 32" aria-hidden="true">
      <rect x="1" y="1" width="30" height="30" rx="8" class="bg" />
      <rect x="7" y="7" width="8" height="8" rx="2" class="cell" />
      <rect x="17" y="7" width="8" height="8" rx="2" class="cell" />
      <rect x="7" y="17" width="8" height="8" rx="2" class="cell" />
      <path d="M22.2 16.5 L18.6 21.6 H21 L19.8 25.5 L23.6 20.2 H21.2 Z" class="bolt" />
    </svg>
    @if (withText()) {
      <span class="name">File<span class="accent">Grid</span></span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 8px;
    }
    .bg {
      fill: var(--accent);
    }
    .cell {
      fill: #fff;
      opacity: 0.92;
    }
    .bolt {
      fill: #ffd34d;
    }
    .name {
      font-weight: 700;
      font-size: 1.1rem;
      letter-spacing: -0.01em;
      color: var(--text);
    }
    .accent {
      color: var(--accent);
    }
  `,
})
export class Logo {
  readonly size = input(28);
  readonly withText = input(true);
}
