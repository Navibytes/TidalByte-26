package com.tidalbyte.stridesync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.tidalbyte.stridesync.MainActivity
import com.tidalbyte.stridesync.R
import com.tidalbyte.stridesync.SLog
import com.tidalbyte.stridesync.session.LiveState
import com.tidalbyte.stridesync.session.SessionHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A foreground service (type mediaPlayback) that hosts the running session, so sensors and the cue keep
 * going with the screen off in a pocket. It holds a partial wake lock for the session and shows an
 * ongoing notification with the live state and a Stop action. It stops itself when the session ends.
 */
class CueService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                createChannel()
                startForeground(NOTIFICATION_ID, buildNotification(SessionHub.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                val spec = SessionHub.takePending()
                if (spec == null) {
                    shutDown()
                    return START_NOT_STICKY
                }
                acquireWakeLock()
                SessionHub.startSession(applicationContext, spec)
                observe()
                SLog.i(TAG, "foreground, session started (${spec.kind})")
            }
            else -> shutDown() // ACTION_STOP, or a null intent after a process restart
        }
        return START_NOT_STICKY
    }

    /** Keeps the notification current, and stops the service once the session is no longer running. */
    private fun observe() {
        observer?.cancel()
        observer = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            var seenRunning = false
            var lastText = ""
            var lastPostMs = 0L
            SessionHub.state.collect { st ->
                if (st.running) seenRunning = true
                if (seenRunning && !st.running) {
                    shutDown()
                    return@collect
                }
                val text = summary(st)
                val now = SystemClock.elapsedRealtime()
                if (text != lastText && now - lastPostMs >= 1000) {
                    lastText = text
                    lastPostMs = now
                    nm.notify(NOTIFICATION_ID, buildNotification(st))
                }
            }
        }
    }

    private fun shutDown() {
        observer?.cancel()
        observer = null
        SessionHub.stopSession()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        SLog.i(TAG, "destroyed")
        super.onDestroy()
    }

    private fun summary(st: LiveState): String = buildString {
        append(st.cueState.label)
        st.cueBpm?.let { append(" · cue %.0f bpm".format(it)) }
        st.spm?.let { append(" · %.0f spm".format(it)) }
        st.syncPct?.let { append(" · %.0f%% in sync".format(it * 100)) }
        if (st.recordingName != null) append(" · recording")
    }

    private fun buildNotification(st: LiveState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CueService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stridesync)
            .setContentTitle("StrideSync session")
            .setContentText(if (st.running) summary(st) else "Starting…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            // Low importance: no sound or vibration. This is an audio app, so the notification must never beep.
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Walking session", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StrideSync:session")
            .apply { acquire(WAKE_LOCK_MAX_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.tidalbyte.stridesync.START"
        const val ACTION_STOP = "com.tidalbyte.stridesync.STOP"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "service"
        private const val WAKE_LOCK_MAX_MS = 2 * 60 * 60 * 1000L // safety timeout: 2 h
    }
}
