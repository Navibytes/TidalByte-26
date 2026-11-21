package com.tidalbyte.stridesync.sensor

import android.content.Context
import android.os.SystemClock
import com.tidalbyte.stridesync.SLog
import com.tidalbyte.stridesync.Tuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/** A recorded walk: either bundled in assets (`replays/…`) or recorded on this phone (`files/recordings/…`). */
data class ReplayRef(val name: String, val assetPath: String? = null, val filePath: String? = null) {
    fun readLines(context: Context): Sequence<String> = when {
        assetPath != null -> context.assets.open(assetPath).bufferedReader().readLines().asSequence()
        filePath != null -> File(filePath).readLines().asSequence()
        else -> emptySequence()
    }
}

/**
 * Plays a recorded CSV ([AccelCsv]) in real time, rebased to the elapsedRealtime clock at start and
 * delivered in batches like a real sensor. The flow completes at the end of the file, which ends the
 * session. A recording can't react to the cue, so replay shows real-data detection and locking, not
 * entrainment.
 */
class ReplaySource(
    private val context: Context,
    private val ref: ReplayRef,
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) : SensorSource {

    override val label = "Replay: ${ref.name}"

    override fun samples(): Flow<AccelSample> = flow {
        val recorded = AccelCsv.parse(ref.readLines(context))
        if (recorded.isEmpty()) {
            SLog.w(TAG, "${ref.name}: no samples")
            return@flow
        }
        SLog.i(TAG, "${ref.name}: %d samples, %.1fs".format(recorded.size, (recorded.last().tNanos - recorded.first().tNanos) / 1e9))
        val offset = clockNanos() - recorded.first().tNanos
        var i = 0
        while (i < recorded.size) {
            val now = clockNanos()
            while (i < recorded.size && recorded[i].tNanos + offset <= now) {
                val s = recorded[i++]
                emit(s.copy(tNanos = s.tNanos + offset))
            }
            if (i < recorded.size) delay(Tuning.SIM_BATCH_MS)
        }
        SLog.i(TAG, "${ref.name}: finished")
    }.flowOn(Dispatchers.Default)

    companion object {
        private const val TAG = "replay"

        /** Bundled replays first, then this phone's recordings (newest first). */
        fun list(context: Context): List<ReplayRef> {
            val bundled = (context.assets.list("replays") ?: emptyArray())
                .filter { it.endsWith(".csv") }
                .sorted()
                .map { ReplayRef(name = it.removeSuffix(".csv"), assetPath = "replays/$it") }
            val recorded = recordingsDir(context).listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
                .sortedByDescending { it.lastModified() }
                .map { ReplayRef(name = it.name.removeSuffix(".csv"), filePath = it.absolutePath) }
            return bundled + recorded
        }

        /** Where live recordings go: /sdcard/Android/data/<pkg>/files/recordings/ (see CLAUDE.md for adb pull). */
        fun recordingsDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }
    }
}
