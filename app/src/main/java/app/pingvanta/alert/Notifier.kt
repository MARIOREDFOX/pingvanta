package app.pingvanta.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.pingvanta.MainActivity
import app.pingvanta.R
import app.pingvanta.data.MonitorEntity
import app.pingvanta.data.MonitorStatus
import app.pingvanta.data.MonitorType
import app.pingvanta.monitor.AlertSink
import app.pingvanta.monitor.CheckOutcome
import java.text.DateFormat
import java.util.Date

object Channels {
    const val UP = "status_up"
    const val DOWN = "status_down"
    const val ALARM = "alarm_active"
    const val ALARM_FALLBACK = "alarm_fallback"
    const val LIVE = "live_monitoring"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val vibration = longArrayOf(0, 250, 150, 250)

        val up = NotificationChannel(UP, "Monitor is back online", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Sent when a monitor changes from DOWN to UP"
            enableVibration(true)
            vibrationPattern = vibration
        }
        val down = NotificationChannel(DOWN, "Monitor went offline", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Sent when a monitor changes from UP to DOWN"
            enableVibration(true)
            vibrationPattern = vibration
        }
        // The alarm service plays its own sound on the alarm stream, so this channel stays silent.
        val alarm = NotificationChannel(ALARM, "Active alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while an alarm is ringing, with an Acknowledge button"
            setSound(null, null)
            enableVibration(false)
        }
        // Used only if Android refuses to start the alarm service from the background.
        val fallback = NotificationChannel(
            ALARM_FALLBACK,
            "Alarm (fallback)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Rings with the alarm sound when the full alarm cannot be started"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
        }
        val live = NotificationChannel(LIVE, "Live monitoring", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing notification while short-interval monitoring is running"
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(up, down, alarm, fallback, live))
    }
}

fun canPostNotifications(context: Context): Boolean {
    val granted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

fun openAppIntent(context: Context, monitorId: Long?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (monitorId != null) putExtra(MainActivity.EXTRA_MONITOR_ID, monitorId)
    }
    val requestCode = (monitorId ?: 0L).toInt()
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Turns a status transition into a notification and, if the monitor asks for it, an alarm. */
class Notifier(private val context: Context) : AlertSink {

    override fun onTransition(monitor: MonitorEntity, outcome: CheckOutcome, previousSuccessAt: Long?, at: Long) {
        val up = monitor.status == MonitorStatus.UP
        val title = if (up) "Website Available" else "Website Unavailable"
        val headline = if (up) "${monitor.name} is now ONLINE!" else "${monitor.name} is now OFFLINE!"
        val body = buildString {
            append(headline).append('\n')
            append(targetLabel(monitor)).append('\n')
            if (up) {
                append("Response Time: ").append(outcome.responseMs?.let { "$it ms" } ?: "n/a").append('\n')
                append("Status: UP\n")
                append("Available since: ").append(formatTime(at))
            } else {
                append("Reason: ").append(outcome.error ?: "Unknown failure").append('\n')
                append("Status: DOWN\n")
                append("Last successful check: ")
                append(previousSuccessAt?.let { formatTime(it) } ?: "never")
            }
        }

        val wantsNotification = if (up) monitor.notifyOnUp else monitor.notifyOnDown
        val wantsAlarm = if (up) monitor.alarmOnUp else monitor.alarmOnDown

        if (wantsNotification) {
            post(
                id = notificationId(monitor.id),
                channel = if (up) Channels.UP else Channels.DOWN,
                title = title,
                headline = headline,
                body = body,
                monitorId = monitor.id,
                colorArgb = if (up) COLOR_UP else COLOR_DOWN,
            )
        }
        if (wantsAlarm) {
            AlarmLauncher.trigger(context, title, body, monitor.id, monitor.repeatAlarm)
        }
    }

    fun sendTest(up: Boolean) {
        val title = if (up) "Website Available" else "Website Unavailable"
        val headline = if (up) "Test monitor is now ONLINE!" else "Test monitor is now OFFLINE!"
        val body = if (up) {
            "$headline\nURL: https://example.com\nResponse Time: 120 ms\nStatus: UP"
        } else {
            "$headline\nURL: https://example.com\nReason: Connection timeout\nStatus: DOWN"
        }
        post(
            id = if (up) TEST_UP_ID else TEST_DOWN_ID,
            channel = if (up) Channels.UP else Channels.DOWN,
            title = title,
            headline = headline,
            body = body,
            monitorId = null,
            colorArgb = if (up) COLOR_UP else COLOR_DOWN,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(
        id: Int,
        channel: String,
        title: String,
        headline: String,
        body: String,
        monitorId: Long?,
        colorArgb: Int,
    ) {
        if (!canPostNotifications(context)) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(title)
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(colorArgb)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(openAppIntent(context, monitorId))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        private const val TEST_UP_ID = 900_001
        private const val TEST_DOWN_ID = 900_002
        const val COLOR_UP = 0xFF22C55E.toInt()
        const val COLOR_DOWN = 0xFFEF4444.toInt()

        fun notificationId(monitorId: Long): Int = 10_000 + (monitorId % 100_000).toInt()

        fun targetLabel(monitor: MonitorEntity): String = when (monitor.type) {
            MonitorType.HTTP -> "URL: ${monitor.target}"
            MonitorType.TCP -> "Host: ${monitor.target}:${monitor.port}"
            MonitorType.PING -> "Host: ${monitor.target}"
        }

        fun formatTime(millis: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    }
}
