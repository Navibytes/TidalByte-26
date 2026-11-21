import { dataTable, sparkline } from '../charts';
import { type Patient, patients } from '../data';
import { fmtAgo, fmtCv, fmtPct, fmtSpm, h, signed } from '../dom';
import type { View } from './common';

export function patientListView(): View {
  const rows = [...patients].sort((a, b) => lastSession(b).localeCompare(lastSession(a)));

  const table = dataTable<Patient>(
    rows,
    [
      { title: 'Patient', value: (p) => p.displayName },
      { title: 'Sessions', value: (p) => String(p.sessions.length), num: true },
      { title: 'Last session', value: (p) => fmtAgo(lastSession(p)) },
      { title: 'Baseline cadence (spm)', value: (p) => fmtSpm(p.sessions.at(-1)!.baselineSpm), num: true },
      { title: 'Change since first', value: (p) => signed(p.sessions.at(-1)!.baselineSpm - p.sessions[0].baselineSpm, 1, ' spm'), num: true },
      { title: 'In sync (last)', value: (p) => fmtPct(p.sessions.at(-1)!.syncPct), num: true },
      { title: 'Stride CV (last)', value: (p) => fmtCv(p.sessions.at(-1)!.meanCv), num: true },
      { title: 'Baseline trend', value: () => '' },
    ],
    (p) => (location.hash = `#/patient/${p.id}`),
  );

  // Bold names and inline sparklines (the table helper only writes text).
  table.querySelectorAll('tbody tr').forEach((tr, i) => {
    const p = rows[i];
    const cells = tr.querySelectorAll('td');
    cells[0].classList.add('name-cell');
    cells[cells.length - 1].append(sparkline(p.sessions.map((s) => s.baselineSpm)));
    tr.setAttribute('aria-label', `Open ${p.displayName}`);
  });

  const el = h(
    'div',
    {},
    h('h1', {}, 'Patients'),
    h('p', { class: 'sub' }, 'Baseline cadence is measured silently at the start of each session, before any cue. Select a patient to see their trends.'),
    h('section', { class: 'card' }, h('div', { class: 'table-wrap' }, table)),
  );
  return { el, mounts: [], title: 'Patients' };
}

function lastSession(p: Patient): string {
  return p.sessions.at(-1)!.startedAt;
}
