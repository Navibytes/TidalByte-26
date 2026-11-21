package com.tidalbyte.stridesync.session

import com.tidalbyte.stridesync.SLog
import com.tidalbyte.stridesync.sensor.AccelCsv
import com.tidalbyte.stridesync.sensor.AccelSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Writes raw samples to an [AccelCsv] file on Dispatchers.IO, so the pipeline thread never touches disk.
 * Pass a long-lived [scope] (not the session's job), so the file still finishes after the session is
 * cancelled.
 */
class SampleRecorder(val file: File, scope: CoroutineScope) {
    private val channel = Channel<AccelSample>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            var n = 0
            try {
                file.bufferedWriter(bufferSize = 64 * 1024).use { w ->
                    w.write(AccelCsv.HEADER)
                    w.newLine()
                    for (s in channel) {
                        w.write(AccelCsv.format(s))
                        w.newLine()
                        n++
                    }
                }
                SLog.i(TAG, "saved $n samples to ${file.absolutePath}")
            } catch (e: Exception) {
                SLog.e(TAG, "recording to ${file.name} failed", e)
            }
        }
    }

    fun add(s: AccelSample) {
        channel.trySend(s)
    }

    /** Non-suspending: safe to call from a cancelled coroutine's finally block. */
    fun close() {
        channel.close()
    }

    private companion object {
        const val TAG = "record"
    }
}
