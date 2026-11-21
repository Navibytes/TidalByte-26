package com.tidalbyte.stridesync.audio

import com.tidalbyte.stridesync.Tuning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class BeatClockTest {
    private val sr = Tuning.AUDIO_SAMPLE_RATE
    private val sound = ClickSynth.render(CueSound.CLICK, sr)
    private fun period(bpm: Double) = sr * 60.0 / bpm

    /** Renders [totalFrames] in chunks of [chunk]. Returns the audio and the beat frames. */
    private fun renderAll(
        clock: BeatClock,
        totalFrames: Int,
        chunk: Int,
        startFrame: Long = 0,
        beats: MutableList<Long> = ArrayList(),
    ): FloatArray {
        val audio = FloatArray(totalFrames)
        val buf = FloatArray(chunk)
        var f = 0
        while (f < totalFrames) {
            val len = minOf(chunk, totalFrames - f)
            clock.render(startFrame + f, buf, len) { beats += it }
            System.arraycopy(buf, 0, audio, f, len)
            f += len
        }
        return audio
    }

    @Test
    fun fixedTempo_beatsOnExactFrames_independentOfChunkSize() {
        val bpm = 70.0 // non-integer period: 41142.857… frames
        val first = 4800.0
        val total = sr * 60
        val results = listOf(1, 240, 4800).map { chunk ->
            val beats = ArrayList<Long>()
            val audio = renderAll(BeatClock(period(bpm), first, sound), total, chunk, beats = beats)
            Pair(beats, audio)
        }
        val expected = (0 until 70).map { n -> (first + n * period(bpm)).roundToLong() }.filter { it < total }
        for ((beats, _) in results) assertEquals(expected, beats)
        // Clicks straddling chunk boundaries render sample-identically to one big chunk.
        assertArrayEquals(results[2].second, results[0].second, 0f)
        assertArrayEquals(results[2].second, results[1].second, 0f)
    }

    @Test
    fun noDriftOverLongSession() {
        val bpm = 97.3
        val beats = ArrayList<Long>()
        val total = sr * 60 * 30 // 30 minutes
        renderAll(BeatClock(period(bpm), 0.0, sound), total, 4800, beats = beats)
        val n = beats.size - 1
        assertEquals((n * period(bpm)).roundToLong(), beats.last())
    }

    @Test
    fun clickWaveformAppearsAtBeatFrame() {
        val clock = BeatClock(period(120.0), 1000.0, sound)
        val audio = renderAll(clock, sr, 240)
        for (i in 0 until 1000) assertEquals(0f, audio[i], 0f) // silence before the first beat
        for (i in sound.indices) assertEquals(sound[i], audio[1000 + i], 1e-7f)
    }

    @Test
    fun tempoChange_onlyAffectsUnrenderedBeats_andIsSlewLimited() {
        val clock = BeatClock(period(90.0), 0.0, sound)
        val beats = ArrayList<Long>()
        val before = renderAll(clock, sr * 10, 240, beats = beats)
        val beatsBefore = beats.toList()
        val reference = renderAll(BeatClock(period(90.0), 0.0, sound), sr * 10, 240)
        assertArrayEquals("rendered audio is unaffected", reference, before, 0f)

        clock.setTargetPeriod(period(60.0))
        renderAll(clock, sr * 30, 240, startFrame = sr * 10L, beats = beats)
        assertEquals(beatsBefore, beats.take(beatsBefore.size))

        val intervals = beats.zipWithNext { a, b -> (b - a).toDouble() }
        for ((a, b) in intervals.zipWithNext()) {
            assertTrue("interval jump $a → $b", abs(b - a) <= Tuning.BEAT_MAX_PERIOD_CHANGE_PCT * a + 1.0)
        }
        assertEquals(period(60.0), intervals.last(), 1.0) // converged to 60 bpm
        val firstChanged = intervals[beatsBefore.size - 1]
        assertTrue("first interval after the change is slewed, not jumped", firstChanged < period(60.0) - 1000)
    }

    @Test
    fun phaseShift_clampedPerBeat_remainderDiscarded() {
        val p = period(100.0) // 28 800 frames
        val clock = BeatClock(p, 0.0, sound)
        val beats = ArrayList<Long>()
        renderAll(clock, 1, 1, beats = beats) // places beat 0 at frame 0
        clock.requestPhaseShift(0.5 * p) // asks for far more than allowed
        renderAll(clock, (4 * p).toInt(), 240, startFrame = 1, beats = beats)
        val iv = beats.zipWithNext { a, b -> b - a }
        val maxShift = Tuning.BEAT_MAX_PHASE_SHIFT_PCT * p
        assertEquals((p + maxShift).roundToLong(), iv[0]) // clamped shift on the next beat
        assertEquals(p.roundToLong(), iv[1]) // remainder discarded; back to the plain period
    }

    @Test
    fun unclampedShift_appliedInFullOnce_thenClampsResume() {
        val p = period(100.0)
        val clock = BeatClock(p, 0.0, sound)
        val beats = ArrayList<Long>()
        renderAll(clock, 1, 1, beats = beats)
        clock.requestPhaseShift(0.3 * p, unclamped = true) // a deliberate re-sync
        renderAll(clock, (2.5 * p).toInt(), 240, startFrame = 1, beats = beats) // places the beats at 1.3p and 2.3p
        clock.requestPhaseShift(0.3 * p) // an ordinary request is clamped again
        renderAll(clock, (2 * p).toInt(), 240, startFrame = (2.5 * p).toLong() + 1, beats = beats)
        val iv = beats.zipWithNext { a, b -> b - a }
        assertEquals((1.3 * p).roundToLong(), iv[0])
        assertEquals(p.roundToLong(), iv[1])
        assertEquals((p * (1 + Tuning.BEAT_MAX_PHASE_SHIFT_PCT)).roundToLong(), iv[2])
    }

    @Test
    fun negativeShift_neverPlacesBeatInAlreadyWrittenAudio() {
        val p = period(100.0)
        val clock = BeatClock(p, 0.0, sound)
        val beats = ArrayList<Long>()
        val dueAt = p.roundToLong()
        renderAll(clock, (dueAt - 10).toInt(), 240, beats = beats) // stop 10 frames before beat 1 is due
        clock.requestPhaseShift(-0.05 * p) // would move it ~1440 frames into the past
        renderAll(clock, 2000, 240, startFrame = dueAt - 10, beats = beats)
        assertEquals(dueAt - 10, beats[1]) // placed at the earliest unwritten frame instead
    }

    @Test
    fun mutedBeatsAdvanceScheduleSilently() {
        val clock = BeatClock(period(120.0), 0.0, sound).apply { gain = 0f }
        val beats = ArrayList<Long>()
        val audio = renderAll(clock, sr * 5, 240, beats = beats)
        assertEquals(10, beats.size)
        assertTrue(audio.all { it == 0f })
    }

    @Test
    fun clickSynth_soundsAreShortBoundedAndPopFree() {
        for (s in CueSound.entries) {
            val buf = ClickSynth.render(s, sr)
            assertTrue("$s length", buf.size in (0.01 * sr).toInt()..(0.1 * sr).toInt())
            assertTrue("$s peak", buf.maxOf { abs(it) } <= 0.9f + 1e-6f)
            assertEquals("$s starts at zero", 0f, buf[0], 1e-6f)
            assertTrue("$s ends near zero", abs(buf.last()) < 0.01f)
        }
    }
}
