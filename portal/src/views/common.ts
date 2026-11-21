import type { Chart } from 'chart.js';
import { h } from '../dom';

export interface View {
  el: HTMLElement;
  /** Chart builders, run after el is attached to the document. */
  mounts: (() => Chart | null)[];
  title: string;
}

export interface Tile {
  label: string;
  value: string;
  unit?: string;
  delta?: string;
  /** good/bad colors the delta by direction × whether that direction is good; neutral leaves it plain. */
  tone?: 'good' | 'bad' | 'neutral';
}

export function kpiRow(tiles: Tile[]): HTMLElement {
  return h(
    'div',
    { class: 'kpis' },
    ...tiles.map((t) =>
      h(
        'div',
        { class: 'tile' },
        h('div', { class: 'label' }, t.label),
        h('div', { class: 'value' }, t.value, t.unit ? h('small', {}, t.unit) : null),
        t.delta ? h('div', { class: `delta ${t.tone ?? 'neutral'}` }, t.delta) : null,
      ),
    ),
  );
}

/** Tone for a delta where "up is good" (or down, when upIsGood = false). Tiny changes stay neutral. */
export function toneFor(delta: number, upIsGood: boolean, epsilon: number): 'good' | 'bad' | 'neutral' {
  if (Math.abs(delta) < epsilon) return 'neutral';
  return delta > 0 === upIsGood ? 'good' : 'bad';
}

export function crumbs(...parts: (string | { label: string; href: string })[]): HTMLElement {
  const nav = h('nav', { class: 'crumbs', 'aria-label': 'Breadcrumb' });
  parts.forEach((p, i) => {
    if (i) nav.append(' / ');
    nav.append(typeof p === 'string' ? h('span', {}, p) : h('a', { href: p.href }, p.label));
  });
  return nav;
}
