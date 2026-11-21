import { chartCard, dataTable, lineChart } from '../charts';
import { type Patient, type Session } from '../data';
import { fmtCv, fmtDate, fmtDateTime, fmtDuration, fmtPct, fmtSpm, h, signed } from '../dom';
import { crumbs, kpiRow, toneFor, type View } from './common';

export function patientView(p: Patient): View {
  const s = p.sessions;
  const first = s[0];
  const last = s[s.length - 1];
  const days = Math.max(1, Math.round((Date.parse(last.startedAt) - Date.parse(first.startedAt)) / 86_400_000));
  const labels = s.map((x) => fmtDate(x.startedAt));
  const title = (i: number) => fmtDateTime(s[i].startedAt);

  const kpis = kpiRow([
    {
      label: 'Baseline cadence',
      value: fmtSpm(last.baselineSpm),
      unit: 'spm',
      delta: `${signed(last.baselineSpm - first.baselineSpm, 1, ' spm')} since ${fmtDate(first.startedAt)}`,
      tone: toneFor(last.baselineSpm - first.baselineSpm, true, 0.5),
    },
    {
      label: 'Cadence with cue (last minute)',
      value: fmtSpm(last.finalSpm),
      unit: 'spm',
      delta: `${signed(last.finalSpm - last.baselineSpm, 1, ' spm')} over that session's baseline`,
      tone: toneFor(last.finalSpm - last.baselineSpm, true, 0.5),
    },
    {
      label: 'In sync with the beat',
      value: fmtPct(last.syncPct),
      delta: `${signed((last.syncPct - first.syncPct) * 100, 0, ' pts')} since ${fmtDate(first.startedAt)}`,
      tone: toneFor(last.syncPct - first.syncPct, true, 0.02),
    },
    {
      label: 'Stride CV (variability)',
      value: fmtCv(last.meanCv),
      delta: `${signed((last.meanCv - first.meanCv) * 100, 1, ' pts')} since ${fmtDate(first.startedAt)}`,
      tone: toneFor(last.meanCv - first.meanCv, false, 0.003),
    },
    { label: 'Sessions', value: String(s.length), delta: `over ${days} days` },
  ]);

  const cadence = chartCard<Session>({
    title: 'Cadence by session',
    note: 'Baseline: walking unaided at the start. With cue: average over the last minute of the cued walk.',
    legend: [
      { label: 'With cue (last minute)', colorVar: '--series-1', kind: 'line' },
      { label: 'Baseline', colorVar: '--series-2', kind: 'line' },
    ],
    rows: s,
    columns: [
      { title: 'Session', value: (x) => fmtDateTime(x.startedAt) },
      { title: 'Baseline (spm)', value: (x) => fmtSpm(x.baselineSpm), num: true },
      { title: 'With cue (spm)', value: (x) => fmtSpm(x.finalSpm), num: true },
      { title: 'Peak (spm)', value: (x) => fmtSpm(x.peakSpm), num: true },
    ],
    build: (canvas) =>
      lineChart({
        canvas,
        labels,
        xType: 'category',
        tooltipTitle: title,
        markers: true,
        yTitle: 'steps / min',
        series: [
          { label: 'With cue', colorVar: '--series-1', data: s.map((x) => x.finalSpm), format: (v) => `${v.toFixed(1)} spm`, endLabel: true },
          { label: 'Baseline', colorVar: '--series-2', data: s.map((x) => x.baselineSpm), format: (v) => `${v.toFixed(1)} spm`, endLabel: true },
        ],
      }),
  });

  const sync = chartCard<Session>({
    title: 'In sync with the beat',
    note: 'Share of steps landing within the sync window of a beat.',
    rows: s,
    columns: [
      { title: 'Session', value: (x) => fmtDateTime(x.startedAt) },
      { title: 'In sync', value: (x) => fmtPct(x.syncPct), num: true },
      { title: 'Mean asynchrony', value: (x) => `${Math.round(x.meanAsyncMs)} ms`, num: true },
    ],
    build: (canvas) =>
      lineChart({
        canvas,
        labels,
        xType: 'category',
        tooltipTitle: title,
        markers: true,
        yMin: 50,
        yMax: 100,
        series: [{ label: 'In sync', colorVar: '--series-1', data: s.map((x) => Math.round(x.syncPct * 100)), format: (v) => `${v}%`, endLabel: true, endFormat: (v) => `${v}%` }],
      }),
  });

  const cv = chartCard<Session>({
    title: 'Stride CV',
    note: 'Stride-to-stride timing variability. Lower is steadier.',
    rows: s,
    columns: [
      { title: 'Session', value: (x) => fmtDateTime(x.startedAt) },
      { title: 'Stride CV', value: (x) => fmtCv(x.meanCv), num: true },
    ],
    build: (canvas) =>
      lineChart({
        canvas,
        labels,
        xType: 'category',
        tooltipTitle: title,
        markers: true,
        yMin: 0,
        series: [{ label: 'Stride CV', colorVar: '--series-1', data: s.map((x) => Math.round(x.meanCv * 1000) / 10), format: (v) => `${v.toFixed(1)}%`, endLabel: true, endFormat: (v) => `${v.toFixed(1)}%` }],
      }),
  });

  const sessions = dataTable<Session>(
    [...s].reverse(),
    [
      { title: 'Session', value: (x) => fmtDateTime(x.startedAt) },
      { title: 'Duration', value: (x) => fmtDuration(x.durationSec), num: true },
      { title: 'Baseline', value: (x) => fmtSpm(x.baselineSpm), num: true },
      { title: 'With cue', value: (x) => fmtSpm(x.finalSpm), num: true },
      { title: 'Peak', value: (x) => fmtSpm(x.peakSpm), num: true },
      { title: 'Ramp steps', value: (x) => String(x.rampSteps), num: true },
      { title: 'In sync', value: (x) => fmtPct(x.syncPct), num: true },
      { title: 'Mean async', value: (x) => `${Math.round(x.meanAsyncMs)} ms`, num: true },
      { title: 'Stride CV', value: (x) => fmtCv(x.meanCv), num: true },
    ],
    (x) => (location.hash = `#/patient/${p.id}/session/${x.id}`),
  );

  const el = h(
    'div',
    {},
    crumbs({ label: 'Patients', href: '#/' }, p.displayName),
    h('h1', {}, p.displayName),
    h('p', { class: 'sub' }, `${s.length} sessions since ${fmtDate(first.startedAt)}. Cadences in steps per minute (spm).`),
    kpis,
    cadence.el,
    h('div', { class: 'grid-2' }, sync.el, cv.el),
    h(
      'section',
      { class: 'card' },
      h('div', { class: 'card-head' }, h('div', { class: 'titles' }, h('h2', {}, 'Sessions'), h('div', { class: 'note' }, 'Newest first. Select a session for its timeline.'))),
      h('div', { class: 'table-wrap' }, sessions),
    ),
  );
  return { el, mounts: [cadence.mount, sync.mount, cv.mount], title: p.displayName };
}
