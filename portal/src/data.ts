// Mock data in the planned Firestore schema (see tools/seed_portal.mjs and CLAUDE.md "Firestore schema").
import raw from './data/mock-data.json';

export type CueState = 'CALIBRATING' | 'LOCKING' | 'RAMPING' | 'HOLDING' | 'STEPPING_BACK' | 'PAUSED';

export interface CueEvent {
  t: number; // seconds since session start
  from: CueState;
  to: CueState;
  reason: string;
}

export interface SeriesPoint {
  t: number; // seconds since session start (one point per 5 s)
  spm: number | null;
  cueBpm: number | null;
  asyncMs: number | null;
  cv: number | null;
  state: CueState;
}

export interface Session {
  id: string;
  startedAt: string;
  durationSec: number;
  baselineSpm: number;
  finalSpm: number;
  peakSpm: number;
  meanCv: number;
  syncPct: number;
  meanAsyncMs: number;
  rampSteps: number;
  events: CueEvent[];
  series: SeriesPoint[];
}

export interface Patient {
  id: string;
  displayName: string;
  createdAt: string;
  latestBaselineSpm: number;
  sessions: Session[];
}

interface MockData {
  generatedAt: string;
  demo: boolean;
  patients: Patient[];
}

const data = raw as unknown as MockData;

export const patients: Patient[] = data.patients;

export function findPatient(id: string): Patient | undefined {
  return patients.find((p) => p.id === id);
}

export function findSession(p: Patient, id: string): Session | undefined {
  return p.sessions.find((s) => s.id === id);
}

export const STATE_LABEL: Record<CueState, string> = {
  CALIBRATING: 'Calibrating',
  LOCKING: 'Locking',
  RAMPING: 'Ramping',
  HOLDING: 'Holding',
  STEPPING_BACK: 'Stepping back',
  PAUSED: 'Paused',
};

/** The CSS custom property that holds each state's color. */
export const STATE_VAR: Record<CueState, string> = {
  CALIBRATING: '--state-calibrating',
  LOCKING: '--state-locking',
  RAMPING: '--state-ramping',
  HOLDING: '--state-holding',
  STEPPING_BACK: '--state-stepping-back',
  PAUSED: '--state-paused',
};
