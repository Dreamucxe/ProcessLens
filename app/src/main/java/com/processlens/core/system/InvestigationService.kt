package com.processlens.core.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.processlens.MainActivity
import com.processlens.R
import com.processlens.domain.model.InvestigationState
import com.processlens.domain.usecase.InvestigationRecorder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts the recording loop in the foreground (Sections 14, 24).
 *
 * An investigation has to keep sampling while the user switches to the app they are
 * investigating — which is the whole point — and from API 26 a background process
 * doing that gets frozen within minutes. A foreground service with a visible
 * notification is the only mechanism Android provides for it, and the notification
 * is honest about what is happening: it shows the sample count, so the user can see
 * the tool is working and stop it from the shade.
 *
 * The service owns no observation logic. It starts and stops
 * [InvestigationRecorder], which is where the sampling lives, so recording is
 * testable without a service and the service has nothing to get wrong.
 */
@AndroidEntryPoint
class InvestigationService : LifecycleService() {

    @Inject lateinit var recorder: InvestigationRecorder

    /** True once [stopRecording] has run, so the death handler does not double-close. */
    private var stoppedCleanly = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_INVESTIGATION_ID, -1L)
                if (id <= 0L) {
                    // Nothing to record: stop rather than sit in the foreground
                    // holding a notification for no work.
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundSafely(buildNotification(samples = 0, events = 0))
                recorder.start(id)
                observeProgress()
            }

            ACTION_STOP -> {
                stopRecording(InvestigationState.COMPLETED)
                return START_NOT_STICKY
            }
        }

        // Deliberately not sticky. If the platform kills the service, restarting it
        // with a null intent would resume sampling into an investigation whose
        // continuity was already broken; the gap would be invisible in the data.
        // Instead the row is reconciled to INTERRUPTED at next launch.
        return START_NOT_STICKY
    }

    /** Keeps the notification's counters in step with what has actually been written. */
    private fun observeProgress() {
        lifecycleScope.launch {
            recorder.state.collectLatest { state ->
                if (state.investigationId == null) return@collectLatest
                notificationManager()?.notify(
                    NOTIFICATION_ID,
                    buildNotification(state.sampleCount, state.eventCount),
                )
            }
        }
    }

    private fun stopRecording(finalState: InvestigationState) {
        stoppedCleanly = true
        lifecycleScope.launch {
            recorder.stop(finalState)
            stopForegroundCompat()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Reached both on a clean stop and when the platform tears the service down.
        // In the latter case the recording did not finish, and saying otherwise would
        // misrepresent the data — so it closes as INTERRUPTED.
        if (!stoppedCleanly) {
            val id = recorder.state.value.investigationId
            if (id != null) {
                lifecycleScope.launch { recorder.stop(InvestigationState.INTERRUPTED) }
            }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------- notification

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // API 34 throws if the service was started from the background, and
            // API 33+ shows nothing when notifications are denied. Recording still
            // runs; the in-app screen remains the source of truth for progress.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(samples: Int, events: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, InvestigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = when {
            samples == 0 -> getString(R.string.notification_recording_starting)
            else -> resources.getQuantityString(
                R.plurals.notification_recording_samples, samples, samples, events,
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(body)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_recording),
            // LOW: the notification exists because the platform requires one, not to
            // interrupt. No sound, no vibration.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_recording_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        const val ACTION_START = "com.processlens.action.START_INVESTIGATION"
        const val ACTION_STOP = "com.processlens.action.STOP_INVESTIGATION"
        const val EXTRA_INVESTIGATION_ID = "investigationId"

        private const val CHANNEL_ID = "investigation_recording"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context, investigationId: Long): Intent =
            Intent(context, InvestigationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INVESTIGATION_ID, investigationId)

        fun stopIntent(context: Context): Intent =
            Intent(context, InvestigationService::class.java).setAction(ACTION_STOP)
    }
}
