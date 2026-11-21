import {
  CategoryScale,
  Chart,
  type ChartDataset,
  Filler,
  LinearScale,
  LineController,
  LineElement,
  type Plugin,
  PointElement,
  Tooltip,
} from 'chart.js';
import { type CueState, STATE_LABEL, STATE_VAR } from './data';
import { cssVar, h } from './dom';

Chart.register(LineController, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Filler);

// ───────────── Plugins ─────────────

export interface StateSegment {
  from: number;
  to: number;
  state: CueState;
}

declare module 'chart.js' {
  interface PluginOptionsByType<TType> {
    crosshair?: { color: string };
    stateBands?: { segments: StateSegment[]; alpha: number; labelColor: string };
    refBand?: { min: number; max: number; color: string; label: string; labelColor: string };
    endLabels?: { labels: (string | null)[]; color: string };
  }
}

/** A hairline that follows the tooltip's x (the crosshair snaps to the nearest data position). */
const crosshair: Plugin = {
  id: 'crosshair',
  afterDatasetsDraw(chart, _args, options) {
    const opts = options as unknown as { color: string };
    const active = chart.tooltip?.getActiveElements() ?? [];
    if (!active.length) return;
    const x = active[0].element.x;
    const { top, bottom } = chart.chartArea;
    const ctx = chart.ctx;
    ctx.save();
    ctx.strokeStyle = opts.color;
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(Math.round(x) + 0.5, top);
    ctx.lineTo(Math.round(x) + 0.5, bottom);
    ctx.stroke();
    ctx.restore();
  },
};

/** Washes the plot behind the lines with each cue state's color, and prints the state's name where it fits. */
const stateBands: Plugin = {
  id: 'stateBands',
  beforeDatasetsDraw(chart, _args, options) {
    const opts = options as unknown as { segments?: StateSegment[]; alpha: number; labelColor: string } | undefined;
    if (!opts?.segments?.length) return;
    const x = chart.scales.x;
    const { top, bottom, left, right } = chart.chartArea;
    const ctx = chart.ctx;
    ctx.save();
    ctx.font = '11px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.textBaseline = 'top';
    for (const seg of opts.segments) {
      const x0 = Math.max(left, x.getPixelForValue(seg.from));
      const x1 = Math.min(right, x.getPixelForValue(seg.to));
      if (x1 <= x0) continue;
      ctx.globalAlpha = opts.alpha;
      ctx.fillStyle = cssVar(STATE_VAR[seg.state]);
      ctx.fillRect(x0, top, x1 - x0, bottom - top);
      ctx.globalAlpha = 1;
      // Identity is never color alone: label the band when the name fits with padding.
      const label = STATE_LABEL[seg.state];
      if (ctx.measureText(label).width + 8 <= x1 - x0) {
        ctx.fillStyle = opts.labelColor;
        ctx.fillText(label, x0 + 4, top + 4);
      }
    }
    ctx.restore();
  },
};

/** A shaded horizontal reference band (the ±60 ms sync window). */
const refBand: Plugin = {
  id: 'refBand',
  beforeDatasetsDraw(chart, _args, options) {
    const opts = options as unknown as { min: number; max: number; color: string; label?: string; labelColor: string } | undefined;
    if (!opts?.label) return;
    const y = chart.scales.y;
    const { left, right } = chart.chartArea;
    const y0 = y.getPixelForValue(opts.max);
    const y1 = y.getPixelForValue(opts.min);
    const ctx = chart.ctx;
    ctx.save();
    ctx.fillStyle = opts.color;
    ctx.fillRect(left, y0, right - left, y1 - y0);
    ctx.font = '11px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.fillStyle = opts.labelColor;
    ctx.textBaseline = 'bottom';
    ctx.textAlign = 'right';
    ctx.fillText(opts.label, right - 4, y0 - 3); // above the band, clear of the line inside it
    ctx.restore();
  },
};

/** The value at each line's last point, printed just right of it (a selective direct label). */
const endLabels: Plugin = {
  id: 'endLabels',
  afterDatasetsDraw(chart, _args, options) {
    const opts = options as unknown as { labels?: (string | null)[]; color: string } | undefined;
    if (!opts?.labels) return;
    const ctx = chart.ctx;
    ctx.save();
    ctx.font = '600 12px system-ui, -apple-system, "Segoe UI", sans-serif';
    ctx.fillStyle = opts.color;
    ctx.textBaseline = 'middle';
    const placed: number[] = [];
    chart.data.datasets.forEach((_ds, i) => {
      const label = opts.labels?.[i];
      if (!label) return;
      const meta = chart.getDatasetMeta(i);
      const pts = meta.data.filter((p) => !(p as unknown as { skip?: boolean }).skip && Number.isFinite(p.y));
      const last = pts[pts.length - 1];
      if (!last) return;
      // Colliding end labels aren't stacked or nudged: the later one is dropped (the legend and tooltip carry it).
      if (placed.some((y) => Math.abs(y - last.y) < 14)) return;
      placed.push(last.y);
      ctx.fillText(label, last.x + 8, last.y);
    });
    ctx.restore();
  },
};

Chart.register(crosshair, stateBands, refBand, endLabels);

// ───────────── Chart construction ─────────────

export interface LineSeries {
  label: string;
  colorVar: string; // CSS custom property, e.g. --series-1
  data: (number | null)[];
  format: (v: number) => string; // tooltip values
  endLabel?: boolean;
  /** The direct label at the line's end. Defaults to the bare number (the axis carries the unit). */
  endFormat?: (v: number) => string;
}

export interface LineChartSpec {
  canvas: HTMLCanvasElement;
  labels: (string | number)[];
  series: LineSeries[];
  /** linear x (numbers, e.g. minutes) or category x (strings, e.g. session dates). */
  xType: 'linear' | 'category';
  xTickFormat?: (v: number) => string;
  xStep?: number;
  xMin?: number;
  tooltipTitle?: (index: number) => string;
  yTitle?: string;
  yMin?: number;
  yMax?: number;
  markers?: boolean;
  stateSegments?: StateSegment[];
  refBand?: { min: number; max: number; label: string };
}

export function lineChart(spec: LineChartSpec): Chart {
  const ink2 = cssVar('--ink-2');
  const muted = cssVar('--muted');
  const grid = cssVar('--grid');
  const axis = cssVar('--axis');
  const surface = cssVar('--surface');
  const ink = cssVar('--ink');
  const font = { family: 'system-ui, -apple-system, "Segoe UI", sans-serif', size: 12 };

  const datasets: ChartDataset<'line', (number | null)[]>[] = spec.series.map((s) => {
    const color = cssVar(s.colorVar);
    return {
      label: s.label,
      data: s.data,
      borderColor: color,
      backgroundColor: color,
      borderWidth: 2,
      borderCapStyle: 'round',
      borderJoinStyle: 'round',
      tension: 0,
      spanGaps: false,
      pointRadius: spec.markers ? 4 : 0,
      pointHoverRadius: spec.markers ? 6 : 4,
      pointBorderColor: surface, // a 2px surface ring keeps markers legible over lines
      pointBorderWidth: spec.markers ? 2 : 1,
      pointHitRadius: 12,
    };
  });

  const xData = spec.xType === 'linear' ? spec.labels.map((v) => Number(v)) : spec.labels;
  const anyEndLabels = spec.series.some((s) => s.endLabel);

  return new Chart(spec.canvas, {
    type: 'line',
    data: { labels: xData, datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      interaction: { mode: 'index', intersect: false },
      layout: { padding: { right: anyEndLabels ? 44 : 8, top: 4 } },
      scales: {
        x: {
          type: spec.xType,
          grid: { display: false },
          border: { color: axis },
          ticks: {
            color: muted,
            font,
            maxRotation: 0,
            autoSkipPadding: 12,
            ...(spec.xStep ? { stepSize: spec.xStep } : {}),
            callback(value) {
              if (spec.xType === 'linear') return spec.xTickFormat ? spec.xTickFormat(Number(value)) : String(value);
              return String(spec.labels[Number(value)] ?? value);
            },
          },
          ...(spec.xType === 'linear' ? { min: spec.xMin ?? Number(xData[0]), max: Number(xData[xData.length - 1]) } : {}),
        },
        y: {
          min: spec.yMin,
          max: spec.yMax,
          grid: { color: grid, lineWidth: 1 },
          border: { display: false },
          ticks: { color: muted, font, maxTicksLimit: 6 },
          title: spec.yTitle ? { display: true, text: spec.yTitle, color: ink2, font } : undefined,
        },
      },
      plugins: {
        legend: { display: false }, // an HTML legend sits above the chart
        tooltip: {
          backgroundColor: surface,
          borderColor: cssVar('--axis'),
          borderWidth: 1,
          titleColor: ink2,
          titleFont: { ...font, weight: 'normal' },
          bodyColor: ink,
          bodyFont: { ...font, size: 13 },
          padding: 10,
          boxPadding: 6,
          usePointStyle: true,
          callbacks: {
            title(items) {
              const i = items[0]?.dataIndex ?? 0;
              return spec.tooltipTitle ? spec.tooltipTitle(i) : String(spec.labels[i]);
            },
            // Values lead, the series name follows.
            label(item) {
              const s = spec.series[item.datasetIndex];
              const v = item.parsed.y;
              return v == null || Number.isNaN(v) ? `—  ${s.label}` : `${s.format(v)}  ${s.label}`;
            },
            labelPointStyle: () => ({ pointStyle: 'line', rotation: 0 }),
          },
        },
        crosshair: { color: axis },
        stateBands: spec.stateSegments ? { segments: spec.stateSegments, alpha: 0.12, labelColor: ink2 } : undefined,
        refBand: spec.refBand ? { ...spec.refBand, color: cssVar('--hover'), labelColor: muted } : undefined,
        endLabels: anyEndLabels
          ? {
              labels: spec.series.map((s) => {
                if (!s.endLabel) return null;
                const last = [...s.data].reverse().find((v) => v != null);
                return last == null ? null : (s.endFormat ?? ((v: number) => v.toFixed(1)))(last);
              }),
              color: ink2,
            }
          : undefined,
      },
    },
  });
}

// ───────────── Card with Chart | Table toggle ─────────────

export interface Column<R> {
  title: string;
  value: (row: R) => string;
  num?: boolean;
}

export interface ChartCardSpec<R> {
  title: string;
  note?: string;
  legend?: { label: string; colorVar: string; kind: 'line' | 'swatch' }[];
  tall?: boolean;
  rows: R[];
  columns: Column<R>[];
  /** Builds the chart once the canvas is in the document. */
  build: (canvas: HTMLCanvasElement) => Chart;
}

export interface Mounted {
  el: HTMLElement;
  mount: () => Chart | null;
}

export function chartCard<R>(spec: ChartCardSpec<R>): Mounted {
  const canvas = h('canvas', { role: 'img', 'aria-label': `${spec.title} chart. The same data is available in the table view.` });
  const box = h('div', { class: spec.tall ? 'chart-box tall' : 'chart-box' }, canvas);
  const tableWrap = h('div', { class: 'table-wrap capped', hidden: true }, dataTable(spec.rows, spec.columns));
  const chartBtn = h('button', { type: 'button', 'aria-pressed': 'true' }, 'Chart');
  const tableBtn = h('button', { type: 'button', 'aria-pressed': 'false' }, 'Table');
  const legend = spec.legend?.length
    ? h(
        'div',
        { class: 'legend' },
        ...spec.legend.map((l) => {
          const key = h('span', { class: l.kind === 'line' ? 'line' : 'swatch' });
          key.style.background = `var(${l.colorVar})`;
          return h('span', { class: 'key' }, key, l.label);
        }),
      )
    : null;
  const show = (table: boolean) => {
    chartBtn.setAttribute('aria-pressed', String(!table));
    tableBtn.setAttribute('aria-pressed', String(table));
    box.hidden = table;
    if (legend) legend.hidden = table;
    tableWrap.hidden = !table;
  };
  chartBtn.addEventListener('click', () => show(false));
  tableBtn.addEventListener('click', () => show(true));

  const el = h(
    'section',
    { class: 'card' },
    h(
      'div',
      { class: 'card-head' },
      h('div', { class: 'titles' }, h('h2', {}, spec.title), spec.note ? h('div', { class: 'note' }, spec.note) : null),
      h('div', { class: 'toggle', role: 'group', 'aria-label': 'View as' }, chartBtn, tableBtn),
    ),
    legend,
    box,
    tableWrap,
  );
  return { el, mount: () => spec.build(canvas) };
}

export function dataTable<R>(rows: R[], columns: Column<R>[], onRow?: (row: R) => void): HTMLTableElement {
  return h(
    'table',
    {},
    h('thead', {}, h('tr', {}, ...columns.map((c) => h('th', { class: c.num ? 'num' : '' }, c.title)))),
    h(
      'tbody',
      {},
      ...rows.map((r) => {
        const tr = h('tr', onRow ? { class: 'link', tabindex: '0' } : {}, ...columns.map((c) => h('td', { class: c.num ? 'num' : '' }, c.value(r))));
        if (onRow) {
          tr.addEventListener('click', () => onRow(r));
          tr.addEventListener('keydown', (e) => {
            if ((e as KeyboardEvent).key === 'Enter') onRow(r);
          });
        }
        return tr;
      }),
    ),
  );
}

/** A small inline sparkline: the history in muted ink, the latest point in the accent color. */
export function sparkline(values: number[], width = 96, height = 28): SVGSVGElement {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('width', String(width));
  svg.setAttribute('height', String(height));
  svg.setAttribute('class', 'spark');
  svg.setAttribute('aria-hidden', 'true');
  if (values.length < 2) return svg;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const pad = 4;
  const x = (i: number) => pad + (i / (values.length - 1)) * (width - 2 * pad);
  const y = (v: number) => height - pad - ((v - min) / (max - min || 1)) * (height - 2 * pad);
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(''));
  path.setAttribute('fill', 'none');
  path.setAttribute('stroke', 'var(--muted)');
  path.setAttribute('stroke-width', '1.5');
  path.setAttribute('stroke-linejoin', 'round');
  path.setAttribute('stroke-linecap', 'round');
  const dot = document.createElementNS(ns, 'circle');
  dot.setAttribute('cx', x(values.length - 1).toFixed(1));
  dot.setAttribute('cy', y(values[values.length - 1]).toFixed(1));
  dot.setAttribute('r', '3.5');
  dot.setAttribute('fill', 'var(--accent)');
  dot.setAttribute('stroke', 'var(--surface)');
  dot.setAttribute('stroke-width', '2');
  svg.append(path, dot);
  return svg;
}
