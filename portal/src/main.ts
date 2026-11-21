import type { Chart } from 'chart.js';
import './style.css';
import { findPatient, findSession } from './data';
import { h } from './dom';
import type { View } from './views/common';
import { patientListView } from './views/patientList';
import { patientView } from './views/patientView';
import { sessionView } from './views/sessionView';

const app = document.getElementById('app')!;
let charts: Chart[] = [];

function route(): View {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  if (parts[0] === 'patient' && parts[1]) {
    const p = findPatient(parts[1]);
    if (p && parts[2] === 'session' && parts[3]) {
      const s = findSession(p, parts[3]);
      if (s) return sessionView(p, s);
    } else if (p) {
      return patientView(p);
    }
  }
  return patientListView();
}

function render(keepScroll = false) {
  charts.forEach((c) => c.destroy());
  charts = [];
  const view = route();
  const header = h(
    'header',
    { class: 'topbar' },
    h('a', { class: 'brand', href: '#/' }, 'StrideSync ', h('span', {}, 'Therapist portal')),
    h('span', { class: 'badge' }, 'Demo data'),
    h('span', { class: 'disclaimer' }, 'Prototype — not a medical device. Patients shown are fictional.'),
  );
  const y = window.scrollY;
  app.replaceChildren(h('div', { class: 'shell' }, header, view.el));
  document.title = `${view.title} · StrideSync Portal`;
  charts = view.mounts.map((m) => m()).filter((c): c is Chart => c !== null);
  window.scrollTo(0, keepScroll ? y : 0);
}

window.addEventListener('hashchange', () => render());
// Charts read colors from CSS tokens, so re-render when the color scheme flips.
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => render(true));
render();
