package app.pingvanta.background

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pingvanta.PingvantaApp
import app.pingvanta.R
import app.pingvanta.alert.Channels
import app.pingvanta.alert.openAppIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that performs checks for monitors whose interval is shorter than
 * WorkManager's 15-minute minimum. It runs only while such a monitor exists and the user has
 * left "Live monitoring" switched on, and it always shows an ongoing notification.
 */
class LiveMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0), type)
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            acquireWakeLock()
            scope.launch { runLoop() }
            scope.launch { watchConnectivity() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val container = (application as PingvantaApp).container
        var shownCount = -1
        while (scope.isActive) {
            val monitors = container.db.monitorDao().getAll()
            val live = container.settings.current().liveMonitoring
            if (!MonitorScheduler.needsLiveService(monitors, live)) {
                stopSelf()
                return
            }
            val active = monitors.filter { !it.paused }
            if (active.size != shownCount) {
                shownCount = active.size
                updateNotification(shownCount)
            }
            val now = System.currentTimeMillis()
            active.filter { monitor ->
                val last = monitor.lastCheckedAt
                last == null || now - last >= monitor.intervalSeconds * 1000L - TICK_MS / 2
            }.forEach { monitor ->
                scope.launch {
                    runCatching { container.engine.runCheck(monitor.id) }
                        .onFailure { Log.w(TAG, "Check failed for ${monitor.id}: ${it.message}") }
                }
            }
            delay(TICK_MS)
        }
    }

    /** When connectivity returns, check everything right away instead of waiting for the next tick. */
    private suspend fun watchConnectivity() {
        val container = (application as PingvantaApp).container
        var wasOnline = container.network.isOnline()
        container.network.online.collectLatest { online ->
            if (online && !wasOnline) {
                container.db.monitorDao().getAll().filter { !it.paused }.forEach { monitor ->
                    scope.launch { runCatching { container.engine.runCheck(monitor.id) } }
                }
            }
            wasOnline = online
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pingvanta:live-monitoring").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(count: Int): Notification {
        val text = if (count > 0) {
            "Checking $count monitor${if (count == 1) "" else "s"} in the background"
        } else {
            "Background checks are running"
        }
        return NotificationCompat.Builder(this, Channels.LIVE)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Live monitoring active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(this, null))
            .build()
    }

    private fun updateNotification(count: Int) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(count), type) }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    companion object {
        private const val TAG = "PingvantaLive"
        const val NOTIFICATION_ID = 41_001
        const val TICK_MS = 10_000L

        /** Whether the service is currently alive in this process. */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * Starting a foreground service is only allowed from the foreground, from BOOT_COMPLETED,
         * or when the app is exempt from battery optimisation. If Android refuses, monitoring
         * continues through WorkManager and the service starts the next time the app is opened.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, LiveMonitorService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Live service start refused: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Live service start refused: ${e.message}")
            false
        }

        fun stop(context: Context) {
            if (!running) return
            context.stopService(Intent(context, LiveMonitorService::class.java))
        }
    }
}
