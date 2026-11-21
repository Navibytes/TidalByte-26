#!/usr/bin/env node
/**
 * Generates believable mock history for the therapist portal and writes it to
 * portal/src/data/mock-data.json. There is no Firestore upload (milestone 6 was skipped), so the portal reads
 * this file.
 *
 * Each session runs a simplified version of the app's RampController (CALIBRATING → LOCKING → RAMPING ⇄
 * HOLDING, STEPPING_BACK, PAUSED) at the app's clinical timings, against a walker who partially entrains up
 * to that day's ability. That way the state bands, events and summary numbers look like real app output.
 * Patients improve gradually over ~3 weeks, with noise and a bad day or two.
 *
 * The schema mirrors the planned Firestore documents (CLAUDE.md, "Firestore schema"):
 *   patients[]: { id, displayName, createdAt, latestBaselineSpm, sessions[] }
 *   session:    { id, startedAt, durationSec, baselineSpm, finalSpm, peakSpm, meanCv, syncPct, meanAsyncMs,
 *                 rampSteps, events[{t, from, to, reason}], series[{t, spm, cueBpm, asyncMs, cv, state}] }
 *
 * The output is deterministic for a given "today". Override it with SEED_TODAY=2026-11-21.
 * Usage: node tools/seed_portal.mjs
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// The app's Tuning values (android/.../Tuning.kt), clinical timings.
const RAMP_STEP_PCT = 0.02;
const MAX_RAMP_PCT = 0.1;
const HOLD_SEC = 45;
const SETTLE_SEC = 20;
const LOCK_MIN_SEC = 20;
const CALIBRATION_STEPS = 20;
const SYNC_WINDOW_MS = 60;
const SYNC_REQUIRED = 0.8;
const SYNC_LOST = 0.5;
const SERIES_DT = 5; // one series point per 5 s, as the app's SessionSummary downsampling

const today = process.env.SEED_TODAY ? new Date(`${process.env.SEED_TODAY}T18:00:00`) : new Date();

/** Fictional patients. progress 0→1 across their sessions drives the gradual improvement. */
const PATIENTS = [
  { id: 'p-eleanor', displayName: 'Eleanor V.', seed: 11, sessions: 15, days: 21, natural: [86, 93], ability: [0.035, 0.085], cv: [0.055, 0.037], badDays: [6], freezeProne: 0.25, lastIsToday: true },
  { id: 'p-harold', displayName: 'Harold M.', seed: 22, sessions: 14, days: 21, natural: [76, 80.5], ability: [0.025, 0.05], cv: [0.072, 0.058], badDays: [4, 10], freezeProne: 0.55 },
  { id: 'p-rosa', displayName: 'Rosa T.', seed: 33, sessions: 16, days: 21, natural: [95, 103], ability: [0.05, 0.11], cv: [0.04, 0.029], badDays: [9], freezeProne: 0.1 },
  { id: 'p-walter', displayName: 'Walter K.', seed: 44, sessions: 5, days: 8, natural: [82, 84], ability: [0.03, 0.04], cv: [0.066, 0.061], badDays: [], freezeProne: 0.4 },
];

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const lerp = (a, b, f) => a + (b - a) * f;
const round1 = (x) => Math.round(x * 10) / 10;
const round3 = (x) => Math.round(x * 1000) / 1000;

function makeRandom(seed) {
  const u = mulberry32(seed);
  const gauss = () => {
    let a = u();
    if (a < 1e-12) a = 1e-12;
    return Math.sqrt(-2 * Math.log(a)) * Math.cos(2 * Math.PI * u());
  };
  return { u, gauss };
}

/** Simulates one session at 1 s resolution. Returns the session document. */
function simulateSession(rnd, p, progress, bad, startedAt, id) {
  const { u, gauss } = rnd;
  const natural = lerp(p.natural[0], p.natural[1], progress) + gauss() * 1.2 - (bad ? 4 : 0);
  const ability = Math.max(0.01, lerp(p.ability[0], p.ability[1], progress) + gauss() * 0.008 - (bad ? 0.025 : 0));
  const cvBase = lerp(p.cv[0], p.cv[1], progress) * (1 + 0.1 * gauss()) * (bad ? 1.35 : 1);
  // Per-step asynchrony spread while keeping up. Patients get more consistent at stepping with the beat over
  // the weeks; bad days are clearly worse.
  const jitterMs = (lerp(44, 24, progress) + (bad ? 14 : 0)) * (1 + 0.12 * gauss());
  const durationSec = Math.round((540 + u() * 300) / SERIES_DT) * SERIES_DT;
  const capFor = (base) => base * (1 + MAX_RAMP_PCT);

  // A freeze episode on some sessions (more often for freeze-prone patients and on bad days).
  const freeze = u() < p.freezeProne * (bad ? 1.8 : 1) ? { at: 150 + Math.floor(u() * (durationSec - 300)), len: 4 + Math.floor(u() * 6) } : null;

  let state = 'CALIBRATING';
  const events = [];
  const transition = (t, to, reason) => {
    events.push({ t, from: state, to, reason });
    state = to;
  };

  const calibSec = Math.round((CALIBRATION_STEPS / natural) * 60);
  const baseline = natural + gauss() * 0.6;
  const maxBpm = capFor(baseline);
  let walker = natural; // the walker's current cadence
  let cue = null; // cue tempo (null while silent)
  let ladder = []; // ramp ladder of tempos
  let stateSince = 0;
  let holdStart = 0;
  let lockAudibleAt = null;
  let asyncMean = 0; // the walker's systematic lag behind the beat, ms
  let rampSteps = 0;
  let pausedFrom = null;
  const stepLog = []; // {t, async} per step while audible
  const recent = []; // asyncs of the last 16 steps, for sync judgments
  let nextStepT = 0;
  const series = [];
  let windowSteps = [];

  const syncFrac = () => (recent.length < 16 ? null : recent.filter((a) => Math.abs(a) <= SYNC_WINDOW_MS).length / recent.length);
  const command = (bpm) => {
    cue = Math.min(bpm, maxBpm);
    recent.length = 0;
  };

  for (let t = 0; t < durationSec; t++) {
    const frozen = freeze && t >= freeze.at && t < freeze.at + freeze.len;

    // Walker cadence: partial pull toward the cue (τ 10 s), capped at the day's ability.
    const abilityCap = natural * (1 + ability);
    const goal = cue == null ? natural : Math.min(natural + 0.9 * (cue - natural), abilityCap);
    walker += (goal - walker) * (1 - Math.exp(-1 / 10));

    // Asynchrony: settles near a small lag while the walker keeps up; grows while the cue outpaces them.
    if (cue != null && !frozen) {
      const deficitPct = Math.max(0, cue / Math.max(walker, 1) - 1 - 0.004);
      if (deficitPct > 0) asyncMean += deficitPct * 600 * (60 / cue); // ms gained per second
      else asyncMean += (12 - asyncMean) * 0.25;
    }

    // Steps in this second.
    if (!frozen) {
      while (nextStepT < t + 1) {
        if (cue != null && state !== 'CALIBRATING') {
          const a = asyncMean + gauss() * jitterMs;
          const wrapped = ((((a + 300) % 600) + 600) % 600) - 300; // a lost walker drifts through the beat
          stepLog.push({ t: nextStepT, async: wrapped });
          recent.push(wrapped);
          if (recent.length > 16) recent.shift();
          windowSteps.push(wrapped);
        } else {
          windowSteps.push(null);
        }
        nextStepT += (60 / walker) * (1 + cvBase * 0.7 * gauss());
      }
    } else {
      nextStepT = t + 1;
    }

    // The state machine (RampController, simplified).
    const sync = syncFrac();
    if (frozen && state !== 'PAUSED' && state !== 'CALIBRATING' && t - freeze.at >= 3) {
      pausedFrom = state;
      transition(t, 'PAUSED', `no steps for 3.0s; tempo frozen at ${round1(cue ?? natural)} bpm`);
    } else if (state === 'PAUSED' && !frozen) {
      transition(t, pausedFrom === 'LOCKING' ? 'LOCKING' : 'HOLDING', 'steps resumed');
      holdStart = t;
      recent.length = 0;
    } else if (state === 'CALIBRATING' && t >= calibSec) {
      transition(t, 'LOCKING', `baseline ${round1(baseline)} spm from ${CALIBRATION_STEPS} steps; cap ${round1(maxBpm)} bpm`);
      stateSince = t;
    } else if (state === 'LOCKING') {
      if (lockAudibleAt == null && t - stateSince >= 2) {
        lockAudibleAt = t;
        command(walker);
        asyncMean = 15;
        events.push({ t, from: 'LOCKING', to: 'LOCKING', reason: `phase acquired, cue audible at ${round1(walker)} bpm` });
      } else if (lockAudibleAt != null) {
        cue = Math.min(walker + gauss() * 0.3, maxBpm); // the beat follows the walker
        if (t - lockAudibleAt >= LOCK_MIN_SEC && sync != null && sync >= SYNC_REQUIRED) {
          transition(t, 'RAMPING', `in sync ${Math.round(sync * 100)}% for ${t - lockAudibleAt}s`);
          ladder = [cue];
          rampUp(t);
        }
      }
    } else if (state === 'RAMPING' || state === 'HOLDING') {
      if (sync != null && sync < SYNC_LOST) stepBack(t, `sync lost (${Math.round(sync * 100)}%)`);
      else if (state === 'RAMPING' && t - holdStart >= HOLD_SEC) {
        if (sync == null || sync < SYNC_REQUIRED) transition(t, 'HOLDING', `sync marginal (${sync == null ? '-' : Math.round(sync * 100) + '%'}), not advancing`);
        else if (cue >= maxBpm - 1e-6) transition(t, 'HOLDING', `at cap ${round1(maxBpm)} bpm`);
        else rampUp(t);
        holdStart = t;
      } else if (state === 'HOLDING' && t - holdStart >= HOLD_SEC && sync != null && sync >= SYNC_REQUIRED && cue < maxBpm - 1e-6) {
        transition(t, 'RAMPING', `in sync ${Math.round(sync * 100)}% through a ${HOLD_SEC}s hold`);
        rampUp(t);
      }
    } else if (state === 'STEPPING_BACK' && t - stateSince >= SETTLE_SEC) {
      transition(t, 'HOLDING', `settled at ${round1(cue)} bpm`);
      holdStart = t;
      recent.length = 0;
    }

    // One series point per 5 s.
    if ((t + 1) % SERIES_DT === 0) {
      const asyncs = windowSteps.filter((a) => a != null);
      const n = windowSteps.length;
      series.push({
        t: t + 1,
        spm: frozen && n === 0 ? null : round1(walker + gauss() * 0.45), // a 5 s cadence average is fairly smooth
        cueBpm: cue == null ? null : round1(cue),
        asyncMs: asyncs.length ? Math.round(asyncs.reduce((s, a) => s + a, 0) / asyncs.length) : null,
        cv: n >= 3 ? round3(Math.max(0.008, cvBase * (1 + 0.25 * gauss()) * (state === 'STEPPING_BACK' ? 1.3 : 1))) : null,
        state,
      });
      windowSteps = [];
    }
  }

  function rampUp(t) {
    const from = ladder[ladder.length - 1];
    const to = Math.min(from * (1 + RAMP_STEP_PCT), maxBpm);
    if (to <= from + 1e-6) {
      transition(t, 'HOLDING', `at cap ${round1(maxBpm)} bpm`);
      holdStart = t;
      return;
    }
    ladder.push(to);
    rampSteps++;
    holdStart = t;
    command(to);
    events.push({ t, from: 'RAMPING', to: 'RAMPING', reason: `ramp step ${ladder.length - 1}: ${round1(from)} → ${round1(to)} bpm (+${round1((to / from - 1) * 100)}%)` });
  }

  function stepBack(t, reason) {
    if (ladder.length <= 1) {
      transition(t, 'LOCKING', `${reason} at base tempo: re-locking to the walker`);
      ladder = [];
      lockAudibleAt = t;
      stateSince = t;
      return;
    }
    ladder.pop();
    transition(t, 'STEPPING_BACK', `${reason}: back to ${round1(ladder[ladder.length - 1])} bpm`);
    stateSince = t;
    command(ladder[ladder.length - 1]);
    asyncMean = 20;
  }

  // The summary, computed from the session like the app's SessionSummary.
  const spms = series.map((s) => s.spm).filter((x) => x != null);
  const lastMinute = series.filter((s) => s.t > durationSec - 60 && s.spm != null).map((s) => s.spm);
  const rolling = series.map((_, i) => series.slice(Math.max(0, i - 5), i + 1).map((s) => s.spm).filter((x) => x != null)).filter((w) => w.length >= 4).map((w) => w.reduce((a, b) => a + b, 0) / w.length);
  const cvs = series.map((s) => s.cv).filter((x) => x != null);
  const inWindow = stepLog.filter((s) => Math.abs(s.async) <= SYNC_WINDOW_MS).length;

  return {
    id,
    startedAt: startedAt.toISOString(),
    durationSec,
    baselineSpm: round1(baseline),
    finalSpm: round1(lastMinute.reduce((a, b) => a + b, 0) / lastMinute.length),
    peakSpm: round1(Math.max(...rolling, ...spms.slice(0, 1))),
    meanCv: round3(cvs.reduce((a, b) => a + b, 0) / cvs.length),
    syncPct: round3(stepLog.length ? inWindow / stepLog.length : 0),
    meanAsyncMs: round1(stepLog.reduce((s, x) => s + Math.abs(x.async), 0) / Math.max(1, stepLog.length)),
    rampSteps,
    events,
    series,
  };
}

function sessionDates(rnd, p) {
  // Spread sessions over the last p.days days, about one per day, skipping a few days. Times 9:00–17:00.
  const { u } = rnd;
  const days = [];
  const lastDaysAgo = p.lastIsToday ? 0 : 1 + Math.floor(u() * 3); // only the demo patient walked today
  for (let d = p.days - 1 + lastDaysAgo; d >= lastDaysAgo; d--) days.push(d);
  while (days.length > p.sessions) days.splice(1 + Math.floor(u() * (days.length - 2)), 1); // keep the first and last day
  return days.map((daysAgo, i) => {
    const dt = new Date(today);
    dt.setDate(dt.getDate() - daysAgo);
    const isLast = i === days.length - 1;
    if (isLast && p.lastIsToday) dt.setHours(Math.max(9, today.getHours() - 1), 10, 0, 0);
    else dt.setHours(9 + Math.floor(u() * 8), Math.floor(u() * 4) * 15, 0, 0);
    return dt;
  });
}

const patients = PATIENTS.map((p) => {
  const rnd = makeRandom(p.seed);
  const dates = sessionDates(rnd, p);
  const sessions = dates.map((d, i) => {
    const progress = dates.length > 1 ? i / (dates.length - 1) : 1;
    return simulateSession(rnd, p, progress, p.badDays.includes(i), d, `${p.id}-s${String(i + 1).padStart(2, '0')}`);
  });
  const created = new Date(dates[0]);
  created.setDate(created.getDate() - 1);
  return {
    id: p.id,
    displayName: p.displayName,
    createdAt: created.toISOString(),
    latestBaselineSpm: sessions[sessions.length - 1].baselineSpm,
    sessions,
  };
});

const out = resolve(dirname(fileURLToPath(import.meta.url)), '../portal/src/data/mock-data.json');
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, JSON.stringify({ generatedAt: new Date().toISOString(), demo: true, patients }));
for (const p of patients) {
  const s = p.sessions;
  console.log(
    `${p.displayName.padEnd(11)} ${String(s.length).padStart(2)} sessions  baseline ${s[0].baselineSpm}→${s.at(-1).baselineSpm} spm  ` +
      `final ${s[0].finalSpm}→${s.at(-1).finalSpm}  sync ${Math.round(s[0].syncPct * 100)}→${Math.round(s.at(-1).syncPct * 100)}%  ` +
      `cv ${(s[0].meanCv * 100).toFixed(1)}→${(s.at(-1).meanCv * 100).toFixed(1)}%  ramp steps ${s.map((x) => x.rampSteps).join(',')}`,
  );
}
console.log(`wrote ${out}`);
