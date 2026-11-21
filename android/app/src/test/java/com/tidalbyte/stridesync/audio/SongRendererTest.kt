package com.tidalbyte.stridesync.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class SongRendererTest {
    private val sr = 48_000

    /** A synthetic song: [nBeats] beats at [bpm] with ±4 ms of jitter, optionally a sharp bump on each beat, and a
     *  quiet 220 Hz tone throughout. */
    private fun song(bpm: Double, nBeats: Int = 40, bumps: Boolean = true, tone: Float = 0.1f): SongTrack {
        val rng = Random(5)
        val period = sr * 60.0 / bpm
        val beats = DoubleArray(nBeats) { k -> 4800 + k * period + if (k == 0) 0.0 else rng.nextDouble(-192.0, 192.0) }
        val n = (beats.last() + period).toInt()
        val samples = ShortArray(n) { i ->
            var v = tone * sin(2 * PI * 220 * i / sr).toFloat()
            if (bumps) {
                val k = beats.indexOfFirst { abs(it - i) < 30 }
                if (k >= 0) v += 0.85f * exp(-0.5 * ((i - beats[k]) / 3.0) * ((i - beats[k]) / 3.0)).toFloat()
            }
            (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return SongTrack("test", bpm, sr, samples, beats, loopStartBeat = 4, loopEndBeat = nBeats - 4)
    }

    private fun period(bpm: Double) = sr * 60.0 / bpm

    private class Rendered(val audio: FloatArray, val beats: List<Long>)

    /** Renders [seconds] through a BeatClock with the song attached. [during] runs before each chunk. */
    private fun render(track: SongTrack, cueBpm: Double, seconds: Double, startMuted: Boolean = false, during: (BeatClock, Long) -> Unit = { _, _ -> }): Rendered {
        val clock = BeatClock(period(cueBpm), firstBeatFrame = 7200.0, sound = FloatArray(1))
        clock.gain = if (startMuted) 0f else 1f
        clock.music = SongRenderer(track, sr)
        val total = (seconds * sr).toInt()
        val audio = FloatArray(total)
        val buf = FloatArray(240)
        val beats = ArrayList<Long>()
        var f = 0
        while (f < total) {
            during(clock, f.toLong())
            val len = minOf(240, total - f)
            clock.render(f.toLong(), buf, len) { beats += it }
            System.arraycopy(buf, 0, audio, f, len)
            f += len
        }
        return Rendered(audio, beats)
    }

    @Test
    fun songBeatsLandOnClockBeats_throughTempoChangesPhaseShiftsAndLoopWraps() {
        val track = song(bpm = 100.0)
        val r = render(track, cueBpm = 90.0, seconds = 70.0) { clock, f ->
            if (f == 20L * sr) clock.setTargetPeriod(period(98.0)) // a ramp-like tempo change
            if (f == 40L * sr) clock.setTargetPeriod(period(84.0))
            if (f % (7L * sr) == 0L && f > 0) clock.requestPhaseShift(900.0) // PLL phase nudges (clamped to 5%)
        }
        var worst = 0L
        for (b in r.beats.drop(2).filter { it < r.audio.size - 200 }) {
            val from = (b - 120).toInt()
            val peak = (from until (b + 120).toInt()).maxBy { r.audio[it] }
            worst = maxOf(worst, abs(peak - b))
        }
        println("song beats vs clock beats over ${r.beats.size} beats (incl. ~3 loop wraps): worst offset $worst frames")
        assertTrue("worst offset $worst frames", worst <= 2)
    }

    @Test
    fun noAudibleDiscontinuities() {
        val track = song(bpm = 100.0, bumps = false, tone = 0.5f)
        val r = render(track, cueBpm = 90.0, seconds = 60.0) { clock, f ->
            if (f == 15L * sr) clock.setTargetPeriod(period(105.0))
            if (f % (5L * sr) == 0L && f > 0) clock.requestPhaseShift(if ((f / sr) % 2 == 0L) 1200.0 else -1200.0)
        }
        // A 220 Hz tone at 0.5 changes at most ~0.02 per sample at these rates. A jump (no crossfade) would be ≫.
        val start = sr // skip the start
        val worst = (start until r.audio.size - 1).maxOf { abs(r.audio[it + 1] - r.audio[it]) }
        println("max sample-to-sample step: %.4f".format(worst))
        assertTrue("discontinuity $worst", worst < 0.05f)
    }

    @Test
    fun mutedIsSilent_thenFadesInAlreadyInPhase() {
        val track = song(bpm = 100.0)
        val unmuteAt = 10L * sr
        val r = render(track, cueBpm = 100.0, seconds = 20.0, startMuted = true) { clock, f -> if (f == unmuteAt) clock.gain = 1f }
        assertTrue("silent while muted", (0 until unmuteAt.toInt()).all { r.audio[it] == 0f })
        val early = (unmuteAt.toInt() until (unmuteAt + 480).toInt()).maxOf { abs(r.audio[it]) }
        assertTrue("fades in, not a jump: $early", early < 0.05f)
        // Once faded in, the song's beats sit on the clock's beats (the position kept advancing while muted).
        val later = r.beats.filter { it > unmuteAt + sr && it < r.audio.size - 200 }
        for (b in later) {
            val peak = ((b - 120).toInt() until (b + 120).toInt()).maxBy { r.audio[it] }
            assertTrue("beat at $b, peak at $peak", abs(peak - b) <= 2)
        }
    }

    @Test
    fun beatMapping_keepsPlaybackRateNearestOne() {
        fun upb(songBpm: Double, cueBpm: Double): Int {
            val track = song(bpm = songBpm, bumps = false)
            val renderer = SongRenderer(track, sr)
            val clock = BeatClock(period(cueBpm), firstBeatFrame = 7200.0, sound = FloatArray(1)).apply { gain = 1f; music = renderer }
            clock.render(0, FloatArray(240))
            return renderer.unitsPerBeat
        }
        assertEquals("100-bpm song at 95 spm: 1 song beat per step", 2, upb(100.0, 95.0))
        assertEquals("170-bpm song at 90 spm: 2 song beats per step", 4, upb(170.0, 90.0))
        assertEquals("60-bpm song at 110 spm: half a song beat per step", 1, upb(60.0, 110.0))
    }
}
