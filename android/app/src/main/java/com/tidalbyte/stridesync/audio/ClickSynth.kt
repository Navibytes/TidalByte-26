package com.tidalbyte.stridesync.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The cue sounds. CLICK and WOODBLOCK are precomputed bursts. MUSIC plays the prepared song (SongRenderer). */
enum class CueSound(val label: String) { CLICK("Click"), WOODBLOCK("Woodblock"), MUSIC("Music") }

object ClickSynth {

    /** Renders [sound] as mono float PCM in [-1, 1], peak-normalized to [peak]. */
    fun render(sound: CueSound, sampleRate: Int, peak: Float = 0.9f): FloatArray = when (sound) {
        CueSound.CLICK, CueSound.MUSIC -> click(sampleRate) // MUSIC falls back to the click if no song is loaded
        CueSound.WOODBLOCK -> woodblock(sampleRate)
    }.also { normalize(it, peak) }

    /** A bright, short tick: two high partials with a fast decay, plus a noise transient for the attack.
     *  About 25 ms. */
    private fun click(sr: Int): FloatArray {
        val n = (0.025 * sr).toInt()
        val rng = Random(1)
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val tone = 0.7 * sin(2 * PI * 2000 * t) + 0.3 * sin(2 * PI * 3150 * t)
            val noise = (rng.nextDouble() * 2 - 1) * exp(-t / 0.0015)
            ((tone * exp(-t / 0.006) + 0.5 * noise) * attack(t) * release(i, n)).toFloat()
        }
    }

    /** A hollow, woody knock: inharmonic partials with a medium decay. About 60 ms. Softer than the
     *  click, and easier on the ear over a long session. */
    private fun woodblock(sr: Int): FloatArray {
        val n = (0.060 * sr).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val s = 1.0 * sin(2 * PI * 820 * t) * exp(-t / 0.014) +
                0.5 * sin(2 * PI * 1360 * t) * exp(-t / 0.009) +
                0.2 * sin(2 * PI * 2390 * t) * exp(-t / 0.005)
            (s * attack(t) * release(i, n)).toFloat()
        }
    }

    /** A 0.3 ms linear fade-in, so the sound doesn't start with a step (a pop). */
    private fun attack(t: Double) = min(1.0, t / 0.0003)

    /** A fade-out over the last 2 ms, so a truncated tail doesn't click. */
    private fun release(i: Int, n: Int): Double {
        val fade = 96
        return if (i >= n - fade) (n - 1 - i).toDouble() / fade else 1.0
    }

    private fun normalize(buf: FloatArray, peak: Float) {
        val max = buf.maxOf { abs(it) }
        if (max > 0f) for (i in buf.indices) buf[i] = buf[i] * (peak / max)
    }
}
