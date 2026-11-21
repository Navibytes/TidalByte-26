#!/usr/bin/env python3
"""
Prepares a user-provided song as the StrideSync cue: converts it and maps its beats.

  python3 tools/prepare_song.py music/<file> [--start SEC] [--end SEC] [--title "Name"] [--preview]

What it does:
  1. Decodes any ffmpeg-readable file to 48 kHz mono 16-bit and peak-normalizes it.
  2. Finds the beat grid: a spectral-flux onset envelope, tempo from autocorrelation (preferring 80–140 bpm),
     and phase by comb alignment. Then it tracks each beat to the local onset peak, which allows slight drift.
  3. Reports the bpm, the beat count and the grid residual, and warns if the tempo isn't steady.
  4. Picks a loop of whole bars (a multiple of 4 beats) and writes
       android/app/src/main/assets/music/song.wav
       android/app/src/main/assets/music/song.json  {title, bpm, sampleRate, beats[], loopStartBeat, loopEndBeat}
     Both paths are gitignored: the repo is public, so the song never goes into git.
  --preview also writes music/preview_clicks.wav: the first 30 s with a click on every detected beat, for an ear
  check.

Needs ffmpeg and numpy.
"""
import argparse
import json
import os
import subprocess
import sys

import numpy as np

OUT_SR = 48_000
ANA_SR = 12_000  # analysis rate (onsets don't need more)
HOP = 64  # 5.3 ms at 12 kHz
WIN = 512


def decode(path, sr, start=None, end=None):
    cmd = ["ffmpeg", "-v", "error"]
    if start is not None:
        cmd += ["-ss", str(start)]
    if end is not None:
        cmd += ["-to", str(end)]
    cmd += ["-i", path, "-ac", "1", "-ar", str(sr), "-f", "f32le", "-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         check=True, capture_output=True, text=True).stdout.strip()
    return float(out)


def onset_envelope(x, lo_hz=None, hi_hz=None):
    """A log-magnitude spectral-flux onset strength, one value per HOP samples (frame centers). An optional band
    limit (e.g. 40–150 Hz for the kick drum)."""
    pad = np.concatenate([np.zeros(WIN // 2), x, np.zeros(WIN // 2)])
    n = 1 + (len(pad) - WIN) // HOP
    window = np.hanning(WIN).astype(np.float32)
    env = np.zeros(n)
    prev = None
    block = 4096
    for b0 in range(0, n, block):
        idx = np.arange(b0, min(n, b0 + block))
        frames = np.stack([pad[i * HOP:i * HOP + WIN] for i in idx]).astype(np.float32) * window
        mag = np.log1p(100 * np.abs(np.fft.rfft(frames, axis=1)))
        if lo_hz is not None:
            freqs = np.fft.rfftfreq(WIN, 1 / ANA_SR)
            mag = mag[:, (freqs >= lo_hz) & (freqs < hi_hz)]
        if prev is not None:
            mag_prev = np.vstack([prev[None, :], mag[:-1]])
        else:
            mag_prev = np.vstack([mag[:1], mag[:-1]])
        flux = np.maximum(mag - mag_prev, 0).sum(axis=1)
        env[idx] = flux
        prev = mag[-1]
    # Remove the slow trend (local mean over ~0.5 s) and keep the positive part.
    k = int(0.5 * ANA_SR / HOP)
    trend = np.convolve(env, np.ones(k) / k, mode="same")
    env = np.maximum(env - trend, 0)
    return env / (env.max() or 1)


def estimate_tempo(env):
    """bpm from the autocorrelation of the onset envelope, with a soft prior centered on 110 bpm."""
    fps = ANA_SR / HOP
    e = env - env.mean()
    ac = np.correlate(e, e, mode="full")[len(e) - 1:]
    ac /= ac[0] or 1
    lags = np.arange(len(ac))
    bpm = np.where(lags > 0, 60 * fps / np.maximum(lags, 1), 0)
    valid = (bpm >= 60) & (bpm <= 200)
    prior = np.exp(-0.5 * (np.log2(np.maximum(bpm, 1e-9) / 110) / 0.6) ** 2)
    score = np.where(valid, ac * prior, -np.inf)
    lag = int(np.argmax(score))
    # Parabolic refinement of the lag.
    if 1 <= lag < len(ac) - 1:
        a, b, c = ac[lag - 1], ac[lag], ac[lag + 1]
        d = 0.5 * (a - c) / (a - 2 * b + c) if (a - 2 * b + c) != 0 else 0
        lag = lag + float(np.clip(d, -0.5, 0.5))
    return 60 * fps / lag, lag


def track_beats(env, period):
    """The best grid phase by comb alignment, then each beat tracked to the local onset peak (±40 ms)."""
    fps = ANA_SR / HOP
    n = len(env)
    best_phase, best_score = 0.0, -1.0
    for ph in np.arange(0, period, 0.25):
        pos = np.arange(ph, n - 1, period)
        score = np.interp(pos, np.arange(n), env).sum()
        if score > best_score:
            best_phase, best_score = ph, score
    win = int(round(0.040 * fps))
    beats = []
    t = best_phase
    p = period
    while t < n - 1:
        lo, hi = max(0, int(t) - win), min(n - 1, int(t) + win)
        seg = env[lo:hi + 1]
        offs = np.arange(lo, hi + 1) - t
        score = seg - 0.15 * np.abs(offs) / win * (seg.max() or 1)  # prefer staying near the prediction
        k = int(np.argmax(score))
        strong = seg[k] > 0.25 * np.median(env[env > 0]) if np.any(env > 0) else False
        tb = (lo + k) if strong else t
        if beats:
            p = 0.9 * p + 0.1 * (tb - beats[-1])  # the local period follows slow drift
            p = float(np.clip(p, 0.85 * period, 1.15 * period))
        beats.append(float(tb))
        t = tb + p
    return np.array(beats) / fps  # seconds


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--start", type=float, default=None, help="seconds into the song to begin (default: skip 5 s)")
    ap.add_argument("--end", type=float, default=None, help="seconds into the song to end (default: drop the last 5 s)")
    ap.add_argument("--title", default=None)
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--shift-half", choices=["auto", "yes", "no"], default="auto",
                    help="move the beat grid half a beat (auto: when the kick drum sits between detected beats)")
    ap.add_argument("--out-dir", default=os.path.join(os.path.dirname(__file__), "../android/app/src/main/assets/music"))
    a = ap.parse_args()

    dur = duration(a.input)
    start = a.start if a.start is not None else (5.0 if dur > 40 else 0.0)
    end = a.end if a.end is not None else (dur - 5.0 if dur > 40 else dur)
    title = a.title or os.path.splitext(os.path.basename(a.input))[0]

    audio = decode(a.input, OUT_SR, start, end)
    peak = np.max(np.abs(audio)) or 1
    audio = audio / peak * 0.89  # about −1 dBFS
    ana = decode(a.input, ANA_SR, start, end)

    env = onset_envelope(ana)
    bpm, lag = estimate_tempo(env)
    beats_s = track_beats(env, lag)

    # Beat trackers often lock onto the off-beat (snare/hi-hat "ands"). The walking pulse is the kick drum, so
    # fold the kick-band onsets by beat phase and move the grid half a beat if the kicks sit between the beats.
    fps = ANA_SR / HOP
    kick = onset_envelope(ana, 40, 150)
    t = np.arange(len(kick)) / fps
    idx = np.searchsorted(beats_s, t) - 1
    ok = (idx >= 0) & (idx < len(beats_s) - 1)
    phase = (t[ok] - beats_s[idx[ok]]) / (beats_s[idx[ok] + 1] - beats_s[idx[ok]])
    on_beat = kick[ok][(phase < 0.0625) | (phase > 0.9375)].sum()
    off_beat = kick[ok][np.abs(phase - 0.5) < 0.0625].sum()
    shift = a.shift_half == "yes" or (a.shift_half == "auto" and off_beat > 1.3 * on_beat)
    if shift:
        beats_s = (beats_s[:-1] + beats_s[1:]) / 2
    kick_note = f"kick drum on-beat/off-beat onset ratio {on_beat / max(off_beat, 1e-9):.2f}" + \
        (" → shifted the grid half a beat onto the kicks" if shift else "")
    intervals = np.diff(beats_s)
    med = float(np.median(intervals))

    # Residual vs a straight-line grid fit (how steady the tempo is), and drift between the first and last quarter.
    k = np.arange(len(beats_s))
    fit = np.polyval(np.polyfit(k, beats_s, 1), k)
    resid_ms = np.abs(beats_s - fit) * 1000
    q = max(4, len(intervals) // 4)
    drift = (np.median(intervals[-q:]) / np.median(intervals[:q]) - 1) * 100

    # A loop of whole bars: from beat 4 (skip the first bar) to the last multiple of 4 that still has a beat after it.
    loop_start = 4 if len(beats_s) > 24 else 0
    loop_end = loop_start + ((len(beats_s) - 1 - loop_start) // 4) * 4

    os.makedirs(a.out_dir, exist_ok=True)
    pcm = np.clip(np.round(audio * 32767), -32768, 32767).astype("<i2")
    import wave
    with wave.open(os.path.join(a.out_dir, "song.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(OUT_SR)
        w.writeframes(pcm.tobytes())
    meta = {
        "title": title,
        "bpm": round(60 / med, 2),
        "sampleRate": OUT_SR,
        "beats": [int(round(b * OUT_SR)) for b in beats_s],
        "loopStartBeat": loop_start,
        "loopEndBeat": loop_end,
        "gridResidualMs": {"median": round(float(np.median(resid_ms)), 1), "p95": round(float(np.percentile(resid_ms, 95)), 1)},
    }
    with open(os.path.join(a.out_dir, "song.json"), "w") as f:
        json.dump(meta, f)

    print(f"song:        {title}  ({start:.1f}–{end:.1f} s of {dur:.1f} s)")
    print(f"tempo:       {60 / med:.2f} bpm (autocorrelation {bpm:.2f}), {len(beats_s)} beats")
    print(f"steadiness:  grid residual median {meta['gridResidualMs']['median']} ms, p95 {meta['gridResidualMs']['p95']} ms; "
          f"tempo drift first→last quarter {drift:+.1f}%")
    print(f"downbeat:    {kick_note}")
    print(f"loop:        beats {loop_start}–{loop_end} ({(loop_end - loop_start) // 4} bars, "
          f"{beats_s[loop_end] - beats_s[loop_start]:.1f} s)")
    print(f"wrote:       {os.path.normpath(a.out_dir)}/song.wav ({len(pcm) * 2 / 1e6:.1f} MB) + song.json")
    if meta["gridResidualMs"]["p95"] > 40 or abs(drift) > 3:
        print("WARNING: the tempo isn't steady. Beats may not lock well; try --start/--end on a steadier section, "
              "or a song with a programmed beat.")
    if not 70 <= 60 / med <= 150:
        print("note: tempo is far from walking cadence. The app will map 2 song beats (or ½) per step to keep the pitch natural.")

    if a.preview:
        n = min(len(audio), 30 * OUT_SR)
        mix = audio[:n].copy()
        click = np.sin(2 * np.pi * 2000 * np.arange(int(0.02 * OUT_SR)) / OUT_SR) * np.exp(-np.arange(int(0.02 * OUT_SR)) / (0.004 * OUT_SR))
        for b in beats_s:
            i = int(b * OUT_SR)
            if i + len(click) < n:
                mix[i:i + len(click)] += 0.5 * click
        mix = np.clip(mix / max(1, np.max(np.abs(mix))) * 32767, -32768, 32767).astype("<i2")
        prev_path = os.path.join(os.path.dirname(a.input) or ".", "preview_clicks.wav")
        with wave.open(prev_path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(OUT_SR)
            w.writeframes(mix.tobytes())
        print(f"preview:     {prev_path} (song + a click on every detected beat, first 30 s)")


if __name__ == "__main__":
    sys.exit(main())
