package app.pingvanta.alert

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pingvanta.PingvantaApp
import app.pingvanta.R

private const val TAG = "PingvantaAlarm"

/**
 * Starts the audible alarm. Android 12+ forbids starting a foreground service from the background,
 * so there are three routes, tried in order:
 *  1. start the service directly (works while the app or the live-monitoring service is in the foreground);
 *  2. fire an exact AlarmManager alarm one second from now, whose receiver is allowed to start it;
 *  3. post a notification on a channel that uses the alarm sound.
 */
object AlarmLauncher {
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_MONITOR_ID = "monitorId"
    const val EXTRA_REPEAT = "repeat"

    fun trigger(context: Context, title: String, text: String, monitorId: Long?, repeat: Boolean) {
        val app = context.applicationContext
        if (startService(app, title, text, monitorId, repeat)) return
        if (scheduleExact(app, title, text, monitorId, repeat)) return
        postFallback(app, title, text, monitorId, repeat)
    }

    fun serviceIntent(context: Context, title: String, text: String, monitorId: Long?, repeat: Boolean): Intent =
        Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_MONITOR_ID, monitorId ?: -1L)
            putExtra(EXTRA_REPEAT, repeat)
        }

    fun startService(context: Context, title: String, text: String, monitorId: Long?, repeat: Boolean): Boolean =
        try {
            ContextCompat.startForegroundService(context, serviceIntent(context, title, text, monitorId, repeat))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Direct alarm start refused: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Direct alarm start refused: ${e.message}")
            false
        }

    fun canUseExactAlarms(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    }

    private fun scheduleExact(context: Context, title: String, text: String, monitorId: Long?, repeat: Boolean): Boolean {
        if (!canUseExactAlarms(context)) return false
        return try {
            val manager = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_MONITOR_ID, monitorId ?: -1L)
                putExtra(EXTRA_REPEAT, repeat)
            }
            val operation = PendingIntent.getBroadcast(
                context,
                ((monitorId ?: 0L) % 100_000).toInt() + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val info = AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 1_000, openAppIntent(context, monitorId))
            manager.setAlarmClock(info, operation)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun postFallback(context: Context, title: String, text: String, monitorId: Long?, repeat: Boolean) {
        if (!canPostNotifications(context)) return
        val notification = NotificationCompat.Builder(context, Channels.ALARM_FALLBACK)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Alarm: $title")
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, monitorId))
            .build()
        if (repeat) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(context).notify(AlarmService.NOTIFICATION_ID + 1, notification)
    }
}

/** Fired by the exact alarm; a receiver in this state may start a foreground service. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(AlarmLauncher.EXTRA_TITLE) ?: "Monitor alert"
        val text = intent.getStringExtra(AlarmLauncher.EXTRA_TEXT).orEmpty()
        val monitorId = intent.getLongExtra(AlarmLauncher.EXTRA_MONITOR_ID, -1L).takeIf { it >= 0 }
        val repeat = intent.getBooleanExtra(AlarmLauncher.EXTRA_REPEAT, false)
        if (!AlarmLauncher.startService(context, title, text, monitorId, repeat)) {
            AlarmLauncher.postFallback(context, title, text, monitorId, repeat)
        }
    }
}

/**
 * Plays the alarm sound on the alarm audio stream (so it follows the phone's Alarm volume and
 * ignores the ringer/silent switch) until acknowledged or until the time limit is reached.
 */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopAlarm() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            // Still satisfy the startForeground contract before stopping.
            promote(buildNotification("Alarm stopped", "", null))
            stopAlarm()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(AlarmLauncher.EXTRA_TITLE) ?: "Monitor alert"
        val text = intent.getStringExtra(AlarmLauncher.EXTRA_TEXT).orEmpty()
        val monitorId = intent.getLongExtra(AlarmLauncher.EXTRA_MONITOR_ID, -1L).takeIf { it >= 0 }
        val repeat = intent.getBooleanExtra(AlarmLauncher.EXTRA_REPEAT, false)

        promote(buildNotification(title, text, monitorId))

        val settings = (application as PingvantaApp).container.settings.state.value
        startSound(settings.alarmSoundUri, loop = true)
        if (settings.vibrate) startVibration()

        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, if (repeat) REPEAT_LIMIT_MS else SINGLE_ALARM_MS)
        return START_NOT_STICKY
    }

    private fun promote(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter foreground: ${e.message}")
        }
    }

    private fun buildNotification(title: String, text: String, monitorId: Long?): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Channels.ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Alarm: $title")
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setColor(Notifier.COLOR_DOWN)
            .setContentIntent(openAppIntent(this, monitorId))
            .setDeleteIntent(stop)
            .addAction(R.drawable.ic_alarm, "Acknowledge", stop)
            .build()
    }

    private fun startSound(customUri: String?, loop: Boolean) {
        releasePlayer()
        val candidates = listOfNotNull(
            customUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            val created = MediaPlayer()
            try {
                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                created.setDataSource(this, uri)
                created.isLooping = loop
                created.prepare()
                created.start()
                player = created
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not play $uri: ${e.message}")
                created.release()
            }
        }
    }

    private fun startVibration() {
        val service = getSystemService(Vibrator::class.java) ?: return
        if (!service.hasVibrator()) return
        vibrator = service
        val pattern = longArrayOf(0, 700, 500)
        service.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }

    private fun stopAlarm() {
        handler.removeCallbacks(stopRunnable)
        releasePlayer()
        vibrator?.cancel()
        vibrator = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        releasePlayer()
        vibrator?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.pingvanta.alarm.START"
        const val ACTION_STOP = "app.pingvanta.alarm.STOP"
        const val NOTIFICATION_ID = 42_001

        /** A non-repeating alarm rings for 30 seconds. */
        const val SINGLE_ALARM_MS = 30_000L

        /** "Repeat until acknowledged" still stops after 15 minutes to protect the battery. */
        const val REPEAT_LIMIT_MS = 15 * 60_000L

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }
}
