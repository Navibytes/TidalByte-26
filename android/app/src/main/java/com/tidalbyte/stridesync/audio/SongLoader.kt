package com.tidalbyte.stridesync.audio

import android.content.Context
import com.tidalbyte.stridesync.SLog
import org.json.JSONObject
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the song prepared by tools/prepare_song.py from assets/music/ (song.wav + song.json). The folder is
 * gitignored, so a fresh clone has no song, and this returns null (the Music option is then disabled).
 */
object SongLoader {
    private const val TAG = "music"

    fun load(context: Context): SongTrack? = try {
        val meta = JSONObject(context.assets.open("music/song.json").bufferedReader().readText())
        val wav = context.assets.open("music/song.wav").use { it.readBytes() }
        val (rate, samples) = parseWav(wav)
        val beatsJson = meta.getJSONArray("beats")
        val beats = DoubleArray(beatsJson.length()) { beatsJson.getLong(it).toDouble() * rate / meta.getInt("sampleRate") }
        SongTrack(
            title = meta.optString("title", "Song"),
            bpm = meta.getDouble("bpm"),
            sampleRate = rate,
            samples = samples,
            beats = beats,
            loopStartBeat = meta.getInt("loopStartBeat"),
            loopEndBeat = meta.getInt("loopEndBeat"),
        ).also { SLog.i(TAG, "loaded '${it.title}': %.1f bpm, %d beats, %.0fs".format(it.bpm, beats.size, samples.size.toDouble() / rate)) }
    } catch (e: FileNotFoundException) {
        SLog.i(TAG, "no song bundled (assets/music/song.wav); Music cue unavailable")
        null
    } catch (e: Exception) {
        SLog.e(TAG, "failed to load song", e)
        null
    }

    /** A minimal RIFF/WAVE reader: 16-bit PCM, mono (the format prepare_song.py writes). */
    private fun parseWav(bytes: ByteArray): Pair<Int, ShortArray> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var p = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val size = buf.getInt(p + 4)
            val body = p + 8
            when (id) {
                "fmt " -> {
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(bits == 16 && channels == 1) { "expected 16-bit mono, got $bits-bit × $channels" }
                    val n = minOf(size, bytes.size - body) / 2
                    val out = ShortArray(n)
                    buf.position(body)
                    buf.asShortBuffer().get(out, 0, n)
                    return rate to out
                }
            }
            p = body + size + (size and 1)
        }
        error("no data chunk")
    }
}
