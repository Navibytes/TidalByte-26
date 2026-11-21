import { chartCard, dataTable, lineChart, type StateSegment } from '../charts';
import { type CueEvent, type CueState, type Patient, type SeriesPoint, type Session, STATE_LABEL, STATE_VAR } from '../data';
import { fmtClock, fmtCv, fmtDateTime, fmtDuration, fmtPct, fmtSpm, h, signed } from '../dom';
import { crumbs, kpiRow, toneFor, type View } from './common';

export function sessionView(p: Patient, s: Session): View {
  const series = s.series;
  // x in minutes; each 5 s point closes its window, so include t = 0 by starting from the first window's start.
  const minutes = series.map((pt) => pt.t / 60);
  const segments = stateSegments(series);
  const statesPresent = (Object.keys(STATE_LABEL) as CueState[]).filter((st) => segments.some((g) => g.state === st));
  const pointTitle = (i: number) => `${fmtClock(series[i].t)} · ${STATE_LABEL[series[i].state]}`;
  const peakCue = Math.max(...series.map((pt) => pt.cueBpm ?? 0));

  const kpis = kpiRow([
    { label: 'Duration', value: fmtDuration(s.durationSec) },
    { label: 'Baseline cadence', value: fmtSpm(s.baselineSpm), unit: 'spm' },
    {
      label: 'With cue (last minute)',
      value: fmtSpm(s.finalSpm),
      unit: 'spm',
      delta: `${signed(s.finalSpm - s.baselineSpm, 1, ' spm')} over baseline`,
      tone: toneFor(s.finalSpm - s.baselineSpm, true, 0.5),
    },
    {
      label: 'Highest cue tempo',
      value: fmtSpm(peakCue),
      unit: 'bpm',
      delta: `${signed((peakCue / s.baselineSpm - 1) * 100, 0, '%')} vs baseline · ${s.rampSteps} ramp steps`,
    },
    { label: 'In sync', value: fmtPct(s.syncPct), delta: `mean asynchrony ${Math.round(s.meanAsyncMs)} ms` },
    { label: 'Stride CV', value: fmtCv(s.meanCv) },
  ]);
  // A y-range with headroom, so ±1 spm of 5-second noise doesn't look like a swing.
  const perMin = series.flatMap((pt) => [pt.spm, pt.cueBpm]).filter((v): v is number => v != null);
  const yMin = Math.floor((Math.min(...perMin, s.baselineSpm) - 2) / 5) * 5;
  const yMax = Math.ceil((Math.max(...perMin) + 2) / 5) * 5;

  const timeline = chartCard<SeriesPoint>({
    title: 'Cadence and cue tempo',
    note: 'Steps per minute and beats per minute share one scale. The shading shows what the cue was doing.',
    tall: true,
    legend: [
      { label: 'Cadence', colorVar: '--series-1', kind: 'line' },
      { label: 'Cue tempo', colorVar: '--series-2', kind: 'line' },
      ...statesPresent.map((st) => ({ label: STATE_LABEL[st], colorVar: STATE_VAR[st], kind: 'swatch' as const })),
    ],
    rows: series,
    columns: [
      { title: 'Time', value: (pt) => fmtClock(pt.t) },
      { title: 'State', value: (pt) => STATE_LABEL[pt.state] },
      { title: 'Cadence (spm)', value: (pt) => (pt.spm == null ? '—' : fmtSpm(pt.spm)), num: true },
      { title: 'Cue (bpm)', value: (pt) => (pt.cueBpm == null ? 'silent' : fmtSpm(pt.cueBpm)), num: true },
    ],
    build: (canvas) =>
      lineChart({
        canvas,
        labels: minutes,
        xType: 'linear',
        xTickFormat: (m) => `${Math.round(m)} min`,
        xStep: 1,
        xMin: 0,
        tooltipTitle: pointTitle,
        yTitle: 'per minute',
        yMin,
        yMax,
        stateSegments: segments,
        series: [
          { label: 'Cadence', colorVar: '--series-1', data: series.map((pt) => pt.spm), format: (v) => `${v.toFixed(1)} spm`, endLabel: true },
          { label: 'Cue tempo', colorVar: '--series-2', data: series.map((pt) => pt.cueBpm), format: (v) => `${v.toFixed(1)} bpm`, endLabel: true },
        ],
      }),
  });

  const timing = chartCard<SeriesPoint>({
    title: 'Step timing vs the beat',
    note: 'Mean asynchrony per 5 s (step time minus beat time). Positive means steps land after the beat.',
    rows: series.filter((pt) => pt.asyncMs != null),
    columns: [
      { title: 'Time', value: (pt) => fmtClock(pt.t) },
      { title: 'State', value: (pt) => STATE_LABEL[pt.state] },
      { title: 'Asynchrony', value: (pt) => `${pt.asyncMs} ms`, num: true },
      { title: 'Stride CV', value: (pt) => (pt.cv == null ? '—' : fmtCv(pt.cv)), num: true },
    ],
    build: (canvas) =>
      lineChart({
        canvas,
        labels: minutes,
        xType: 'linear',
        xTickFormat: (m) => `${Math.round(m)} min`,
        xStep: 1,
        xMin: 0,
        tooltipTitle: pointTitle,
        yTitle: 'ms',
        yMin: -300,
        yMax: 300,
        refBand: { min: -60, max: 60, label: '±60 ms sync window' },
        series: [{ label: 'Asynchrony', colorVar: '--series-1', data: series.map((pt) => pt.asyncMs), format: (v) => `${signed(v, 0, ' ms')}` }],
      }),
  });

  const eventsTable = dataTable<CueEvent>(s.events, [
    { title: 'Time', value: (e) => fmtClock(e.t) },
    { title: 'Change', value: (e) => (e.from === e.to ? STATE_LABEL[e.to] : `${STATE_LABEL[e.from]} → ${STATE_LABEL[e.to]}`) },
    { title: 'Reason', value: (e) => e.reason },
  ]);

  const el = h(
    'div',
    {},
    crumbs({ label: 'Patients', href: '#/' }, { label: p.displayName, href: `#/patient/${p.id}` }, fmtDateTime(s.startedAt)),
    h('h1', {}, `Session · ${fmtDateTime(s.startedAt)}`),
    h('p', { class: 'sub' }, `${p.displayName} · baseline ${fmtSpm(s.baselineSpm)} spm · the tempo cap was baseline + 10%`),
    kpis,
    timeline.el,
    timing.el,
    h(
      'section',
      { class: 'card' },
      h('div', { class: 'card-head' }, h('div', { class: 'titles' }, h('h2', {}, 'What the cue did'), h('div', { class: 'note' }, 'Every state change and ramp step, with the reason the app logged.'))),
      h('div', { class: 'table-wrap capped' }, eventsTable),
    ),
  );
  return { el, mounts: [timeline.mount, timing.mount], title: `${p.displayName} · session` };
}

/** Contiguous runs of the same state, in minutes. Each 5 s point covers the window that ends at its t. */
function stateSegments(series: SeriesPoint[]): StateSegment[] {
  const out: StateSegment[] = [];
  for (const pt of series) {
    const from = (pt.t - 5) / 60;
    const to = pt.t / 60;
    const last = out[out.length - 1];
    if (last && last.state === pt.state) last.to = to;
    else out.push({ from: Math.max(from, series[0].t / 60), to, state: pt.state });
  }
  return out;
}
