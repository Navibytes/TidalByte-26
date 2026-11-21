package com.tidalbyte.stridesync.sensor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A freeze-of-gait episode: no steps for [durationSec], starting [startSec] after the walker starts. */
data class FreezeEpisode(val startSec: Double, val durationSec: Double)

/**
 * The synthetic gait signal parameters. Amplitudes are m/s² in the world vertical axis, before the
 * detector's filtering.
 */
data class WalkerConfig(
    /** Mean cadence, steps/min. */
    val cadenceSpm: Double = 90.0,
    /** Peak of the heel-strike transient. */
    val impactAmp: Double = 1.4,
    /** Vertical center-of-mass oscillation at the step frequency (peaks near heel strike). */
    val swayAmp: Double = 0.9,
    /** Fractional amplitude difference between near-side and far-side strikes (the phone sits in one pocket). */
    val asymmetry: Double = 0.2,
    /** Push-off bump about 20% of an interval after each strike, as a fraction of the strike amplitude. */
    val pushOffFrac: Double = 0.35,
    /** Per-axis white noise while walking (body jiggle, clothing). */
    val walkNoise: Double = 0.12,
    /** Per-axis white noise while standing (the sensor's own noise floor). */
    val restNoise: Double = 0.02,
    /** Coefficient of variation of inter-step intervals (gait variability). */
    val intervalCv: Double = 0.03,
    /** Typical amplitude of slow cadence drift, spm. */
    val driftSpm: Double = 1.5,
    /** Trembling-in-place during freezes. */
    val freezeTremorAmp: Double = 0.15,
    val freezeTremorHz: Double = 5.5,
    /** The walker stands still for this long before the first step, s. */
    val startDelaySec: Double = 1.5,
    val freezes: List<FreezeEpisode> = emptyList(),
    val sampleRateHz: Double = 100.0,
    /** Timestamp jitter, µs. Hardware timestamps aren't perfectly regular. */
    val timestampJitterUs: Double = 300.0,
    val seed: Long = 1L,

    // ── Entrainment to the cue (used only when a beat source is attached and beats are audible) ──
    /** Whether the walker responds to the cue at all. Turn it off to show a walker who ignores the beat. */
    val entrains: Boolean = true,
    /** The walker's cadence approaches the cue tempo with this time constant, s. */
    val entrainTauSec: Double = 10.0,
    /** Partial entrainment: the cadence settles at natural + strength × (cue − natural). Below 1, the walker
     *  keeps a pull toward its own preferred cadence. That pull anchors the pair while the beat follows the
     *  walker (LOCKING); without it, beat and walker random-walk together. */
    val entrainStrength: Double = 0.9,
    /** Cadence drift is scaled by this while entrained. Rhythmic cueing reduces gait variability. */
    val cuedDriftScale: Double = 0.3,
    /** Per-step phase correction: this fraction of the predicted asynchrony to the nearest heard beat is
     *  removed from the next interval. Human tapping studies put it around 0.2–0.5. */
    val phaseCorrection: Double = 0.3,
    /** The fastest cadence the walker can sustain is natural × (1 + this). A cue beyond it can't be
     *  followed: steps fall behind, sync is lost, and the ramp has to step back. */
    val abilityHeadroomPct: Double = 0.06,
) {
    companion object {
        /** A typical demo patient: slowish, moderately weak strikes, some variability. */
        val PARKINSONIAN = WalkerConfig()

        /** Slow, shuffling, low-amplitude gait. The hard case for the detector. */
        val SHUFFLING = WalkerConfig(
            cadenceSpm = 62.0,
            impactAmp = 0.45,
            swayAmp = 0.3,
            asymmetry = 0.3,
            pushOffFrac = 0.45,
            walkNoise = 0.08,
            intervalCv = 0.05,
            driftSpm = 2.0,
        )

        /** For comparison: a brisk, strong, regular gait. */
        val HEALTHY = WalkerConfig(
            cadenceSpm = 112.0,
            impactAmp = 3.5,
            swayAmp = 2.2,
            asymmetry = 0.1,
            pushOffFrac = 0.3,
            walkNoise = 0.25,
            intervalCv = 0.02,
        )
    }
}

/**
 * A deterministic synthetic gait signal generator. Pure Kotlin (no Android imports), so tests can drive
 * it directly. Every heel strike it produces is recorded in [trueStrikes] as ground truth.
 *
 * The signal model, in the world frame: vertical = g + strike transients + push-off bumps + a step-rate
 * sway, with small lateral and fore-aft components. A fixed "pocket" rotation maps it into the phone frame,
 * and per-axis noise is added there.
 */
class SimulatedWalker(private val config: WalkerConfig, private val startNanos: Long) {

    /**
     * When set, it returns the times (ns, sample timebase) at which recent *audible* beats are heard. The
     * walker uses only beats heard up to the current sample time, so it partially entrains: its cadence
     * moves toward the cue tempo (time constant [WalkerConfig.entrainTauSec], up to its ability ceiling),
     * and each step corrects part of its asynchrony. With no audible cue, it walks at its own cadence,
     * exactly as without a beat source.
     */
    @Volatile
    var beatSource: (() -> List<Long>)? = null

    private class Strike(val t: Double, val amp: Double, val interval: Double, val index: Int)

    private val rng = Random(config.seed)
    private val periodNs = (1e9 / config.sampleRateHz).roundToLong()
    private var sampleIndex = 0L
    private var lastEmittedT = Long.MIN_VALUE

    /** The cadence the walker is heading toward, spm. Safe to change from another thread (UI slider);
     *  the walker eases toward it over a few steps. */
    @Volatile
    var targetCadenceSpm: Double = config.cadenceSpm

    private var cadence = config.cadenceSpm
    private var drift = 0.0
    private var strikeCount = 0
    private var nowSec = 0.0

    /** The walker's current underlying cadence, spm (before drift and jitter). */
    val currentCadenceSpm: Double get() = cadence

    // Strikes whose waveform may still affect the signal, plus the one upcoming strike (always last).
    private val strikes = ArrayDeque<Strike>()
    private var nextStrikeT = config.startDelaySec

    private val _trueStrikes = ArrayList<Long>()

    /** Ground-truth heel-strike times, ns, in the same timebase as the samples. Includes only strikes up to
     *  ~0.1 s past the latest generated sample. */
    val trueStrikes: List<Long> get() = _trueStrikes

    /** The (unjittered) time of the next sample, ns. A real-time source emits while this ≤ now. */
    val nextSampleNanos: Long get() = startNanos + sampleIndex * periodNs

    // Phone orientation in a front trouser pocket: mostly upright, tilted and twisted a little.
    private val rot: Array<DoubleArray> = rotation(pitchDeg = 78.0, rollDeg = 12.0, yawDeg = 25.0)

    fun next(): AccelSample {
        val jitter = (gaussian() * config.timestampJitterUs * 1000).roundToLong()
        var tNs = startNanos + sampleIndex * periodNs + jitter
        if (tNs <= lastEmittedT) tNs = lastEmittedT + 1
        lastEmittedT = tNs
        sampleIndex++

        val ts = (tNs - startNanos) / 1e9
        nowSec = ts
        scheduleStrikesThrough(ts + 0.1)
        while (strikes.size > 1 && strikes.first().t < ts - MAX_SWAY_SPAN_SEC) strikes.removeFirst()

        val freezing = inFreeze(ts)
        val walking = ts >= config.startDelaySec && !freezing

        var vertical = 0.0
        for (s in strikes) {
            val dt = ts - s.t
            if (dt < -0.06 || dt > 0.6) continue
            vertical += s.amp * strikeShape(dt)
            vertical += s.amp * config.pushOffFrac * bump(dt - 0.2 * s.interval, 0.035)
        }

        // Step-rate sway, phase-locked to the strikes on either side of ts. Only while actually stepping.
        var lateral = 0.0
        var foreAft = 0.0
        val prev = strikes.lastOrNull { it.t <= ts }
        val nextT = strikes.firstOrNull { it.t > ts }?.t ?: nextStrikeT
        if (walking && prev != null && nextT - prev.t < MAX_SWAY_SPAN_SEC) {
            val phase = ((ts - prev.t) / (nextT - prev.t)).coerceIn(0.0, 1.0)
            vertical += config.swayAmp * cos(2 * PI * phase)
            val stridePhase = (phase + (prev.index % 2)) / 2.0
            lateral = 0.35 * config.swayAmp * sin(2 * PI * stridePhase)
            foreAft = 0.5 * config.swayAmp * sin(2 * PI * phase + 0.8)
        }
        if (freezing) {
            vertical += config.freezeTremorAmp * sin(2 * PI * config.freezeTremorHz * ts)
        }

        val world = doubleArrayOf(lateral, foreAft, G + vertical)
        val noise = if (walking) config.walkNoise else config.restNoise
        val px = dot(rot[0], world) + noise * gaussian()
        val py = dot(rot[1], world) + noise * gaussian()
        val pz = dot(rot[2], world) + noise * gaussian()
        return AccelSample(tNs, px.toFloat(), py.toFloat(), pz.toFloat())
    }

    private fun scheduleStrikesThrough(horizon: Double) {
        while (nextStrikeT <= horizon) {
            val t = nextStrikeT
            val interval = nextInterval(t)
            var following = t + interval
            val freeze = config.freezes.firstOrNull { following >= it.startSec && following < it.startSec + it.durationSec }
            if (freeze != null) following = freeze.startSec + freeze.durationSec + 0.4

            val nearSide = strikeCount % 2 == 0
            val sideScale = if (nearSide) 1.0 + config.asymmetry / 2 else 1.0 - config.asymmetry / 2
            val ampScale = (1.0 + 0.08 * gaussian()).coerceIn(0.7, 1.3)
            strikes.addLast(Strike(t, config.impactAmp * sideScale * ampScale, interval, strikeCount))
            _trueStrikes.add(startNanos + (t * 1e9).roundToLong())
            strikeCount++
            nextStrikeT = following
        }
    }

    private fun nextInterval(strikeT: Double): Double {
        val cue = if (config.entrains) heardCue() else null
        if (cue == null) {
            cadence += (targetCadenceSpm - cadence) * 0.25
        } else {
            val ability = targetCadenceSpm * (1 + config.abilityHeadroomPct)
            val pulled = targetCadenceSpm + config.entrainStrength * (60.0 / cue.periodSec - targetCadenceSpm)
            val goal = minOf(pulled, ability)
            val prelim = 60.0 / (cadence + drift)
            cadence += (goal - cadence) * (1 - exp(-prelim / config.entrainTauSec))
        }
        val driftAmp = if (cue == null) config.driftSpm else config.driftSpm * config.cuedDriftScale
        drift = drift * 0.97 + gaussian() * driftAmp * 0.25
        val spm = (cadence + drift).coerceIn(20.0, 250.0)
        val nominal = 60.0 / spm
        var interval = (nominal * (1.0 + config.intervalCv * gaussian())).coerceIn(nominal * 0.6, nominal * 1.4)
        if (cue != null) {
            // Correct part of the predicted asynchrony to the nearest heard (or anticipated) beat. The walker
            // can't step faster than its ability ceiling, so a too-fast cue makes it fall behind.
            val predicted = strikeT + interval
            interval -= config.phaseCorrection * cue.asyncTo(predicted)
            interval = interval.coerceAtLeast(60.0 / (targetCadenceSpm * (1 + config.abilityHeadroomPct)))
        }
        return interval
    }

    /** The audible beat grid as heard so far, in seconds since the walker started. */
    private class HeardCue(val beats: List<Double>, val periodSec: Double) {
        /** t − nearest beat (extrapolating the grid past the last heard beat), wrapped to ±period/2. */
        fun asyncTo(t: Double): Double {
            val last = beats.last()
            val nearest = if (t > last) {
                last + Math.round((t - last) / periodSec) * periodSec
            } else {
                beats.minBy { kotlin.math.abs(it - t) }
            }
            return t - nearest
        }
    }

    private fun heardCue(): HeardCue? {
        val src = beatSource ?: return null
        val beats = src().map { (it - startNanos) / 1e9 }.filter { it <= nowSec }.takeLast(8)
        if (beats.size < 3) return null
        val intervals = beats.zipWithNext { a, b -> b - a }.takeLast(4).sorted()
        val period = intervals[intervals.size / 2]
        if (period <= 0.0 || nowSec - beats.last() > 2 * period) return null // the cue has stopped
        return HeardCue(beats, period)
    }

    private fun inFreeze(ts: Double): Boolean =
        config.freezes.any { ts >= it.startSec && ts < it.startSec + it.durationSec }

    /** Heel-strike transient: a sharp positive impact followed by a smaller, broader rebound. */
    private fun strikeShape(dt: Double): Double =
        bump(dt - 0.02, 0.022) - 0.35 * bump(dt - 0.085, 0.04)

    private fun bump(x: Double, sigma: Double): Double = exp(-0.5 * (x / sigma) * (x / sigma))

    private fun gaussian(): Double {
        // Box–Muller transform.
        var u = rng.nextDouble()
        if (u < 1e-12) u = 1e-12
        return sqrt(-2.0 * ln(u)) * cos(2 * PI * rng.nextDouble())
    }

    private companion object {
        const val G = 9.80665

        /** Strikes further apart than this aren't consecutive steps (a freeze or a stop), so there's no sway. */
        const val MAX_SWAY_SPAN_SEC = 2.5

        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        /** Rotation matrix (world → phone): yaw about Z, then pitch about X, then roll about Y. */
        fun rotation(pitchDeg: Double, rollDeg: Double, yawDeg: Double): Array<DoubleArray> {
            val (p, r, y) = listOf(pitchDeg, rollDeg, yawDeg).map { it * PI / 180 }
            val rz = arrayOf(
                doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0),
            )
            val rx = arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(p), -sin(p)), doubleArrayOf(0.0, sin(p), cos(p)),
            )
            val ry = arrayOf(
                doubleArrayOf(cos(r), 0.0, sin(r)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(r), 0.0, cos(r)),
            )
            return mul(ry, mul(rx, rz))
        }

        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i ->
            DoubleArray(3) { j -> a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] }
        }
    }
}
