package com.tidalbyte.stridesync.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.tidalbyte.stridesync.SLog
import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.cue.CueOutput

/**
 * A synthesized metronome on an [AudioTrack] (48 kHz mono 16-bit, MODE_STREAM, USAGE_MEDIA), fed by a
 * dedicated writer thread at URGENT_AUDIO priority.
 *
 * The writer keeps only about [Tuning.AUDIO_WRITE_AHEAD_MS] queued past the playback head, so tempo and
 * phase changes are heard quickly. [BeatClock] places beats on exact sample frames.
 *
 * Timebase: frames map to *elapsedRealtime* nanos (the SensorEvent.timestamp base) like this:
 * `AudioTrack.getTimestamp()` gives (framePosition, nanoTime) in the System.nanoTime base. A clock offset
 * `elapsedRealtimeNanos − nanoTime`, sampled once at start, converts that, and then
 * [Tuning.OUTPUT_LATENCY_MS] is added.
 *
 * Setters are thread-safe and can be called from any thread.
 */
class MetronomeEngine : CueOutput {

    /** A placed beat. [tNanos] is its comparison time in elapsedRealtime nanos (DAC presentation +
     *  OUTPUT_LATENCY_MS). [audible] is false for beats placed while muted. */
    data class Beat(val index: Long, val frame: Long, val tNanos: Long, val audible: Boolean)

    private val sr = Tuning.AUDIO_SAMPLE_RATE
    private val lock = Any()
    private val sounds = CueSound.entries.associateWith { ClickSynth.render(it, sr) }

    // Guarded by lock.
    private var clock: BeatClock? = null
    private var clockOffsetNs = 0L
    private var anchorFrame = 0L
    private var anchorNanoTime = 0L
    private var hasHwTimestamp = false
    private val recent = ArrayDeque<Beat>()

    @Volatile private var running = false
    private var writer: Thread? = null

    @Volatile var bpm: Double = Tuning.CUE_DEFAULT_BPM
        private set

    @Volatile var sound: CueSound = CueSound.CLICK
        private set

    @Volatile override var muted: Boolean = false
        private set

    /** Called on the audio thread when a beat is placed, about a write-ahead before it's heard. Keep it
     *  cheap: hand off to another thread. */
    @Volatile var onBeat: ((Beat) -> Unit)? = null

    val isRunning: Boolean get() = running

    fun start(initialBpm: Double) {
        if (running) return
        bpm = sanitize(initialBpm)
        val writeAheadFrames = sr * Tuning.AUDIO_WRITE_AHEAD_MS / 1000
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBuf, writeAheadFrames * 2 * 2))
            .build()

        synchronized(lock) {
            // First beat 50 ms after the initial write-ahead fills, so it isn't at the very start of the stream.
            clock = BeatClock(periodFor(bpm), firstBeatFrame = writeAheadFrames + 0.05 * sr, sound = sounds.getValue(sound))
                .also { it.gain = if (muted) 0f else Tuning.CUE_GAIN }
            clockOffsetNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            hasHwTimestamp = false
            recent.clear()
        }
        running = true
        writer = Thread({ writerLoop(track) }, "StrideSync-audio").apply { start() }
        SLog.i(TAG, "start bpm=%.1f bufferFrames=%d minBufBytes=%d".format(bpm, track.bufferSizeInFrames, minBuf))
    }

    fun stop() {
        if (!running) return
        running = false
        writer?.join(1000)
        writer = null
        SLog.i(TAG, "stop")
    }

    fun setBpm(newBpm: Double) {
        if (newBpm.isNaN()) return
        bpm = sanitize(newBpm)
        synchronized(lock) { clock?.setTargetPeriod(periodFor(bpm)) }
    }

    /** Moves the next unplayed beat by [ns] (positive = later), clamped per beat. Replaces any pending
     *  request. Used by the PLL. */
    override fun requestPhaseShiftNs(ns: Long) {
        synchronized(lock) { clock?.requestPhaseShift(ns * sr / 1e9) }
    }

    override val periodNs: Double
        get() = synchronized(lock) { clock?.targetPeriodFrames?.let { it * 1e9 / sr } } ?: (60e9 / bpm)

    override fun setPeriodNs(periodNs: Double) {
        if (!(periodNs > 0.0) || !periodNs.isFinite()) return
        setBpm(60e9 / periodNs)
    }

    override fun recentBeatTimes(): List<Long> = synchronized(lock) { recent.map { frameToElapsedNanosLocked(it.frame) } }

    /** When the recent audible beats actually reach the ear (elapsedRealtime ns): comparison time minus the
     *  detector lag that OUTPUT_LATENCY_MS includes. Feeds the simulated walker's entrainment. */
    fun audibleBeatsHeardNs(): List<Long> = synchronized(lock) {
        recent.filter { it.audible }.map { frameToElapsedNanosLocked(it.frame) - Tuning.DETECTOR_LAG_MS * 1_000_000L }
    }

    fun setSound(s: CueSound) {
        sound = s
        synchronized(lock) { clock?.sound = sounds.getValue(s) }
    }

    override fun setMuted(m: Boolean) {
        muted = m
        synchronized(lock) { clock?.gain = if (m) 0f else Tuning.CUE_GAIN }
    }

    /** The most recent placed beats (up to [RECENT_BEATS]), oldest first, with heard times recomputed from
     *  the latest hardware timestamp. */
    fun recentBeats(): List<Beat> = synchronized(lock) {
        recent.map { it.copy(tNanos = frameToElapsedNanosLocked(it.frame)) }
    }

    /** The predicted heard time of a frame, in elapsedRealtime nanos. */
    fun frameToElapsedNanos(frame: Long): Long = synchronized(lock) { frameToElapsedNanosLocked(frame) }

    private fun frameToElapsedNanosLocked(frame: Long): Long =
        anchorNanoTime + ((frame - anchorFrame) * 1e9 / sr).toLong() + clockOffsetNs + Tuning.OUTPUT_LATENCY_MS * 1_000_000L

    private fun writerLoop(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = Tuning.AUDIO_CHUNK_FRAMES
        val fbuf = FloatArray(chunk)
        val sbuf = ShortArray(chunk)
        val writeAhead = sr.toLong() * Tuning.AUDIO_WRITE_AHEAD_MS / 1000
        val ts = AudioTimestamp()
        val placed = ArrayList<Beat>(2)
        var written = 0L
        var playing = false
        var lastTsPoll = 0L
        var lastStats = System.nanoTime()
        var lastBeatT = Long.MIN_VALUE

        try {
            while (running) {
                val head = if (playing) track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL else 0L
                if (written - head < writeAhead) {
                    placed.clear()
                    synchronized(lock) {
                        val c = clock ?: return
                        c.render(written, fbuf, chunk) { frame ->
                            val beat = Beat(c.beatCount, frame, frameToElapsedNanosLocked(frame), c.gain > 0f)
                            recent.addLast(beat)
                            if (recent.size > RECENT_BEATS) recent.removeFirst()
                            placed += beat
                        }
                    }
                    for (i in 0 until chunk) sbuf[i] = (fbuf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    var off = 0
                    while (off < chunk && running) {
                        val n = track.write(sbuf, off, chunk - off)
                        if (n < 0) {
                            SLog.e(TAG, "AudioTrack.write error $n")
                            running = false
                            break
                        }
                        off += n
                    }
                    written += chunk

                    if (!playing && written >= writeAhead) {
                        synchronized(lock) {
                            // Rough anchor until the first hardware timestamp arrives (within a few hundred ms).
                            anchorFrame = 0L
                            anchorNanoTime = System.nanoTime()
                        }
                        track.play()
                        playing = true
                    }

                    for (b in placed) {
                        val interval = if (lastBeatT == Long.MIN_VALUE) "-" else "%.1f".format((b.tNanos - lastBeatT) / 1e6)
                        lastBeatT = b.tNanos
                        SLog.d(TAG, "beat #${b.index} frame=${b.frame} t=${b.tNanos} interval=${interval}ms bpm=%.1f".format(bpm))
                        onBeat?.invoke(b)
                    }
                } else {
                    Thread.sleep(2)
                }

                val now = System.nanoTime()
                if (playing && now - lastTsPoll > TS_POLL_NS) {
                    lastTsPoll = now
                    if (track.getTimestamp(ts)) {
                        synchronized(lock) {
                            anchorFrame = ts.framePosition
                            anchorNanoTime = ts.nanoTime
                            if (!hasHwTimestamp) SLog.i(TAG, "first hw timestamp: frame=${ts.framePosition}")
                            hasHwTimestamp = true
                        }
                    }
                }
                if (now - lastStats > STATS_NS) {
                    lastStats = now
                    val headNow = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                    SLog.d(
                        TAG,
                        "stats queued=%.0fms underruns=%d hwTs=%b bpm=%.1f muted=%b"
                            .format((written - headNow) * 1000.0 / sr, track.underrunCount, hasHwTimestamp, bpm, muted),
                    )
                }
            }
        } catch (e: InterruptedException) {
            // Shutting down.
        } catch (e: Exception) {
            SLog.e(TAG, "writer crashed", e)
        } finally {
            running = false
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun periodFor(bpm: Double) = sr * 60.0 / bpm

    private fun sanitize(b: Double) = b.coerceIn(Tuning.ENGINE_MIN_BPM, Tuning.ENGINE_MAX_BPM)

    private companion object {
        const val TAG = "audio"
        const val RECENT_BEATS = 32
        const val TS_POLL_NS = 200_000_000L
        const val STATS_NS = 5_000_000_000L
    }
}
